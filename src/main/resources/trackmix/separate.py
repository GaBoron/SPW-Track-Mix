"""Run the pinned BS-RoFormer SW Fixed checkpoint in an isolated worker."""
import argparse
import gc
import hashlib
import math
import os
from pathlib import Path
import sys
import urllib.request

REVISION = "a443a2985534b3bc815ef54a5d446c6a0390f974"
BASE = f"https://huggingface.co/enerjazzer/BS-ROFO-SW-Fixed/resolve/{REVISION}/"
WEIGHTS = "BS-Rofo-SW-Fixed.ckpt"
CONFIG = "BS-Rofo-SW-Fixed.yaml"
WEIGHTS_HASH = "24e7d35ee9c64415673d3fd33e06a67cac2c103c5df6267ba1576459c775916e"
CONFIG_HASH = "f9fada9f94e5ba2d2e4600196299459294bc5f532b314c209cc156ac63e4329b"
WEIGHTS_BYTES = 699412152
RATE = 44100
MODEL_ORDER = ("bass", "drums", "other", "vocals", "guitar", "piano")
CACHE_ORDER = (3, 1, 0, 4, 5, 2)


def progress(stage, fraction=0):
    print(f"trackmix\t{stage}\t{max(0, min(fraction, 1)):.6f}", flush=True)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(directory, name, verify, expected_bytes):
    target = directory / name
    if target.is_file() and verify(target):
        return target
    pending = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(BASE + name, timeout=40) as source, pending.open("wb") as output:
        received = 0
        while block := source.read(1024 * 1024):
            received += len(block)
            if received > expected_bytes:
                raise ValueError(f"Unexpected download size for {name}")
            output.write(block)
            progress("download", received / expected_bytes)
    if not verify(pending):
        raise ValueError(f"Download integrity check failed for {name}")
    os.replace(pending, target)
    return target


def model_files(directory):
    directory.mkdir(parents=True, exist_ok=True)
    progress("verify")
    checkpoint = fetch(directory, WEIGHTS, lambda p: p.stat().st_size == WEIGHTS_BYTES
                       and digest(p) == WEIGHTS_HASH, WEIGHTS_BYTES)
    configuration = fetch(directory, CONFIG, lambda p: p.stat().st_size == 4613
                          and digest(p) == CONFIG_HASH, 4613)
    return checkpoint, configuration


def resize(audio, source_rate, target_rate, frames=None):
    import numpy as np
    from scipy.signal import resample_poly

    if source_rate != target_rate:
        divisor = math.gcd(source_rate, target_rate)
        audio = resample_poly(audio, target_rate // divisor, source_rate // divisor,
                              axis=0, window=("kaiser", 10.0))
    if frames is not None and len(audio) < frames:
        audio = np.pad(audio, ((0, frames - len(audio)),) + ((0, 0),) * (audio.ndim - 1))
    return audio if frames is None else audio[:frames]


def demix(model, audio, chunk, hop, stft_hop, device, fp16, progress_base, progress_scale):
    import numpy as np
    import torch

    frames = len(audio)
    fade = chunk - hop
    starts = [0]
    while starts[-1] + chunk < frames:
        starts.append(starts[-1] + hop)
    result = np.zeros((frames, 6, 2), dtype=np.float32)
    weights = np.zeros(frames, dtype=np.float32)
    stage = "gpu16" if fp16 else "gpu32" if device == "cuda" else "cpu"
    progress(stage, progress_base)
    with torch.inference_mode():
        for index, start in enumerate(starts):
            end = min(start + chunk, frames)
            count = end - start
            block = torch.tensor(audio[start:end].T.copy(), device=device).unsqueeze(0)
            # This model's ISTFT round trips only the complete STFT hops.
            padded = max(4096, math.ceil(count / stft_hop) * stft_hop)
            if count < padded:
                block = torch.nn.functional.pad(block, (0, padded - count))
            with torch.autocast(device_type=device, dtype=torch.float16, enabled=fp16):
                estimate = model(block)
            if estimate.ndim != 4 or estimate.shape[:3] != (1, 6, 2) or estimate.shape[-1] < count:
                raise ValueError(f"Unexpected separator shape {tuple(estimate.shape)}")
            block_result = estimate[0, :, :, :count].float().cpu().numpy().transpose(2, 0, 1)
            if not np.isfinite(block_result).all():
                raise RuntimeError("Inference returned non-finite samples")

            ramp = min(fade, count)
            window = np.ones(count, dtype=np.float32)
            if ramp and index > 0:
                window[:ramp] *= np.arange(1, ramp + 1, dtype=np.float32) / (fade + 1)
            if ramp and end < frames:
                window[-ramp:] *= np.arange(ramp, 0, -1, dtype=np.float32) / (fade + 1)
            result[start:end] += block_result * window[:, None, None]
            weights[start:end] += window
            progress(stage, progress_base + progress_scale * (index + 1) / len(starts))
    result /= weights[:, None, None]
    return result


def separate(args):
    import numpy as np
    import torch
    import yaml
    from audio_separator.separator.roformer.roformer_loader import RoformerLoader

    checkpoint, configuration = model_files(args.models)
    class ModelConfigLoader(yaml.SafeLoader):
        pass

    ModelConfigLoader.add_constructor(
        "tag:yaml.org,2002:python/tuple",
        lambda loader, node: tuple(loader.construct_sequence(node, deep=True)),
    )
    config = yaml.load(configuration.read_text(encoding="utf-8"), Loader=ModelConfigLoader)
    if (config["audio"]["sample_rate"] != RATE or config["audio"]["num_channels"] != 2
            or tuple(config["training"]["instruments"]) != MODEL_ORDER):
        raise ValueError("The installed checkpoint configuration does not declare the expected six tracks")
    torch.set_num_threads(max(1, min(6, os.cpu_count() or 1)))
    torch.backends.cuda.matmul.allow_tf32 = args.quick

    source_size = args.input.stat().st_size
    if source_size == 0 or source_size % 8:
        raise ValueError("Incomplete stereo float PCM input")
    frame_count = source_size // 8
    with args.input.open("rb") as source:
        pcm = np.fromfile(source, dtype="<f4").reshape(-1, 2)
    if not np.isfinite(pcm).all():
        raise ValueError("Decoded PCM contains invalid samples")
    audio = resize(pcm, args.rate, RATE)
    del pcm

    progress("load")
    result = RoformerLoader().load_model(str(checkpoint), config, device="cpu")
    if not result.success:
        raise RuntimeError(result.error_message)
    model = result.model.eval()
    chunk = int(config["audio"]["chunk_size"])
    stft_hop = int(config["model"]["stft_hop_length"])
    attempts = []
    if not args.cpu and torch.cuda.is_available():
        if args.fp16 or args.quick:
            attempts.append(("cuda", True, chunk))
        attempts.append(("cuda", False, chunk))
        if len(audio) > chunk // 2:
            attempts.append(("cuda", False, chunk // 2))
    attempts.append(("cpu", False, chunk))

    for device, fp16, segment in attempts:
        estimates = None
        try:
            model.to(device)
            quality = args.quality and not args.quick
            overlap = 4 if quality else int(config["inference"]["num_overlap"])
            # Retain a short crossfade at every join while processing fewer chunks.
            hop = segment * 7 // 8 if args.quick else segment // overlap
            first_weight = 0.5 if quality else 1.0
            estimates = demix(model, audio, segment, hop, stft_hop, device, fp16, 0, first_weight)
            if quality:
                other = demix(model, audio[:, ::-1], segment, hop, stft_hop, device, fp16, 0.5, 0.5)
                estimates += other[:, :, ::-1]
                estimates *= 0.5
            break
        except RuntimeError as error:
            if device == "cpu":
                raise
            print(f"{device} inference failed: {error}", file=sys.stderr, flush=True)
        # Release tensors and exception tracebacks before the next device/precision attempt.
        del estimates
        model.to("cpu")
        gc.collect()
        torch.cuda.empty_cache()
        progress("fallback")

    progress("finish")
    native = resize(estimates[:, CACHE_ORDER, :], RATE, args.rate, frame_count)
    if not np.isfinite(native).all():
        raise ValueError("The resampled result contains invalid PCM")
    with args.output.open("wb") as output:
        native.astype("<f4", copy=False).tofile(output)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", type=Path, required=True)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--rate", type=int, default=RATE)
    parser.add_argument("--cpu", action="store_true")
    parser.add_argument("--fp16", action="store_true")
    parser.add_argument("--quality", action="store_true")
    parser.add_argument("--quick", action="store_true")
    args = parser.parse_args()
    if args.input is None:
        model_files(args.models)
    elif args.output is None or not 8000 <= args.rate <= 384000:
        parser.error("--output and a supported sample rate are required")
    else:
        separate(args)
    progress("done", 1)


if __name__ == "__main__":
    main()

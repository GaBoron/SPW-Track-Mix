package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class PcmChecks {
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED;
    private static final float SOURCE = 0.25f;
    private static final int FRAMES = 1700;

    public static void main(String[] args) throws Exception {
        try (var arena = Arena.ofConfined()) {
            var samples = new float[FRAMES * StemCache.FLOATS_PER_FRAME];
            for (int frame = 0; frame < FRAMES; frame++) {
                for (int lane = 0; lane < MixSettings.STEMS; lane++) {
                    int start = frame * StemCache.FLOATS_PER_FRAME + lane * 2;
                    samples[start] = 0.05f * (lane + 1);
                    samples[start + 1] = -samples[start];
                }
            }
            var entry = new StemCache.Entry(Path.of("fixture"), 44100, FRAMES,
                    new float[]{0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f}, null, false);
            var window = new StemCache.Window(entry, 0, samples);
            var buffer = arena.allocate(500 * AudioFormat.FLOAT_FRAME_BYTES);
            var mixer = new RealtimeStemMixer(44100);
            var original = MixSettings.DEFAULT;

            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, original);
            near(buffer.getAtIndex(FLOAT, 995), SOURCE, "original is exact");

            var vocals = original.withPreset(MixSettings.Preset.VOCALS);
            mixer.process(buffer, 500, 500, entry, window, vocals);
            near(buffer.getAtIndex(FLOAT, 0), SOURCE + (0.05f - SOURCE) / 441, "vocal fade starts");
            near(buffer.getAtIndex(FLOAT, 998), 0.05f, "vocals only");
            near(buffer.getAtIndex(FLOAT, 999), -0.05f, "stereo preserved");

            var custom = original.withVolume(1, 125).withSolo(0).withSolo(1).withMuted(0);
            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, window, custom);
            near(buffer.getAtIndex(FLOAT, 998), 0.125f, "mute overrides multiple solos and gain adjusts");
            var accompaniment = original.withPreset(MixSettings.Preset.INSTRUMENTAL);
            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, accompaniment);
            near(buffer.getAtIndex(FLOAT, 998), 0.995f, "fixed headroom prevents clipping");

            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, null, custom);
            require(buffer.getAtIndex(FLOAT, 998) == 0, "pending seek leaked original PCM");
            fill(buffer, SOURCE);
            mixer.process(buffer, 1000, 500, entry, window, custom);
            require(Math.abs(buffer.getAtIndex(FLOAT, 0)) < 0.001f, "seek fade did not start at zero");
            fill(buffer, SOURCE);
            mixer.process(buffer, 0, 500, entry, window, original.withPreset(MixSettings.Preset.CUSTOM));
            require(buffer.getAtIndex(FLOAT, 998) == SOURCE, "default custom mix must be untouched");
        }
        mixerControlChecks();
        loudnessChecks();
        spectrumChecks();
        cacheChecks();
        cacheMigrationChecks();
        playbackSessionChecks();
        queueChecks();
        System.out.println("Mixer, loudness, cached PCM, source-positioned FFT per-track resets, and queued separation verified.");
    }

    private static void mixerControlChecks() {
        var custom = MixSettings.DEFAULT.withVolume(0, 64).withVolume(1, 117);
        var muted = custom.withMuted(0);
        require(muted.effectiveVolume(0) == 0 && (muted.muteMask() & 1) != 0,
                "mute must zero the displayed fader");
        require(muted.withMuted(0).effectiveVolume(0) == 64, "unmute must restore the trimmed level");
        var raised = muted.withVolume(0, 90);
        require(raised.effectiveVolume(0) == 90 && raised.muteMask() == 0,
                "raising the fader must clear mute");

        var lowered = custom.withVolume(1, 0);
        require(lowered.effectiveVolume(1) == 0 && lowered.muteMask() == 2, "zero fader must select mute");
        require(lowered.withMuted(1).effectiveVolume(1) == 117, "zero fader must remember the previous level");

        var solo = custom.withSolo(0);
        require(solo.effectiveVolume(0) == 64 && solo.effectiveVolume(1) == 0,
                "solo must zero excluded faders");
        var added = solo.withVolume(2, 42);
        require(added.soloMask() == 5 && added.effectiveVolume(0) == 64 && added.effectiveVolume(2) == 42
                && added.effectiveVolume(1) == 0, "raising an excluded fader must keep the other solos");
        require(added.withMuted(2).effectiveVolume(2) == 0, "mute must override solo");
        require(added.withSolo(2).withSolo(0).effectiveVolume(1) == 117,
                "clearing solo must restore the filtered levels");

        var backing = custom.withPreset(MixSettings.Preset.INSTRUMENTAL);
        require(backing.muteMask() == 1 && backing.effectiveVolume(0) == 0 && backing.effectiveVolume(1) == 100,
                "instrumental controls must reflect the preset");
        require(backing.withPreset(MixSettings.Preset.CUSTOM).effectiveVolume(1) == 117,
                "selecting custom must recall the previous custom edit");
        var edited = backing.withVolume(2, 120);
        require(edited.preset == MixSettings.Preset.CUSTOM && edited.effectiveVolume(0) == 0
                && edited.effectiveVolume(1) == 100 && edited.effectiveVolume(2) == 120,
                "editing a preset must preserve its other levels");

        var vocals = custom.withPreset(MixSettings.Preset.VOCALS);
        require(vocals.soloMask() == 1 && vocals.effectiveVolume(0) == 100 && vocals.effectiveVolume(1) == 0,
                "vocal controls must reflect the preset");
        require(vocals.withSolo(0).original(), "clearing the vocal solo at unity must restore the original");
    }

    private static void loudnessChecks() throws IOException {
        int rate = 48000;
        var stems = ByteBuffer.allocate(StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
        var reference = ByteBuffer.allocate(AudioFormat.FLOAT_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var analysis = new LoudnessProfile.Analysis(rate);
        for (int frame = 0; frame < rate; frame++) {
            float sample = (float) Math.sin(2 * Math.PI * 1000 * frame / rate);
            stems.putFloat(0, sample * .1f).putFloat(4, sample * .1f);
            stems.putFloat(8, sample * .2f).putFloat(12, sample * .2f);
            reference.putFloat(0, sample * .4f).putFloat(4, sample * .4f);
            analysis.accept(stems, 0, reference, 0);
        }
        LoudnessProfile profile = analysis.result();
        near(profile.gainFor(MixSettings.DEFAULT.withPreset(MixSettings.Preset.VOCALS)), 4f, "solo loudness");
        near(profile.gainFor(MixSettings.DEFAULT.withSolo(0).withSolo(1)), 4f / 3, "combined loudness");
        near(profile.gainFor(MixSettings.DEFAULT.withSolo(0).withVolume(0, 50)), 4f, "manual fader remains relative");
    }

    private static void spectrumChecks() {
        int rate = 48000;
        int frames = 8192;
        var tap = new MixedSpectrum(rate);
        try (var arena = Arena.ofConfined()) {
            var pcm = arena.allocate((long) frames * AudioFormat.FLOAT_FRAME_BYTES);
            for (int frame = 0; frame < frames; frame++) {
                float signal = .4f * (float) Math.sin(2 * Math.PI * 128 * frame / frames);
                pcm.setAtIndex(FLOAT, 2L * frame, signal);
                pcm.setAtIndex(FLOAT, 2L * frame + 1, signal);
            }
            tap.write(pcm, 0, frames);
            Object[] frame = tap.at(85);
            var bass = (float[]) frame[1];
            var detail = (float[]) frame[2];
            require((int) frame[0] == rate && bass.length == 4096 && detail.length == 1024,
                    "spectrum format differs from AMLL's bands");
            near(bass[128], .4f, "mixed FFT amplitude");
            near(detail[32], .4f, "mixed detail FFT amplitude");
            require(bass[256] < .0001f, "spectrum contains a tone absent from the selected mix");
            fill(pcm, 0);
            tap.write(pcm, rate, frames);
            require(((float[]) tap.at(85)[1]).length == 0, "seek exposed old spectrum");
            require(((float[]) tap.at(1085)[1])[128] == 0f, "silent mix must produce a silent spectrum");
        }
    }

    private static void cacheChecks() throws IOException {
        Path directory = Files.createTempDirectory("spw-track-mix-cache-check-").toAbsolutePath().normalize();
        Path cacheDir = directory.resolve("cache");
        Path retired = directory.resolve("retired");
        try (var cache = new StemCache(cacheDir, path ->
                Files.move(path, Files.createTempDirectory(retired, "entry-").resolve(path.getFileName())))) {
            Files.createDirectory(retired);
            Path source = Files.writeString(directory.resolve("track"), "first source");
            Path other = Files.writeString(directory.resolve("other"), "second source");
            Path raw = directory.resolve("六轨.f32");
            int cacheFrames = 2 * 8192 + 353;
            var data = ByteBuffer.allocate(cacheFrames * StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
            for (int frame = 0; frame < cacheFrames; frame++) {
                for (int stem = 0; stem < MixSettings.STEMS; stem++) {
                    data.putFloat(frame % 5 == 0 ? -0f : .125f);
                    data.putFloat(frame % 7 == 0 ? 0f : -.125f);
                }
            }
            Files.write(raw, data.array());
            var identity = StemCache.Identity.read(source);
            long compressedBudget = data.capacity() / 2;
            var rough = cache.publish(identity, 48000, cacheFrames, raw, null, directory, compressedBudget, true);
            require(rough.quick() && cache.lookup(identity).quick(), "rough extraction was not reusable");
            var entry = cache.publish(identity, 48000, cacheFrames, raw, null, directory, compressedBudget, false);
            require(!cache.lookup(identity).quick() && cache.lookup(identity).path().equals(entry.path())
                    && Files.isRegularFile(rough.path()), "standard output must take priority over the rough result");
            var unnamed = cache.catalog(null, "").getFirst();
            require(unnamed.title().isEmpty() && unnamed.displayName().startsWith("旧缓存"),
                    "a cache created before the title index must still be manageable");
            var named = cache.catalog(identity.digest(), "测试歌曲").getFirst();
            require(named.bytes() == Files.size(rough.path()) + Files.size(entry.path())
                    && named.quick() && named.standard(), "the catalog did not combine both profiles and their sizes");
            try (var reopened = new StemCache(cacheDir, WindowsRecycle::recycle)) {
                require(reopened.catalog(null, "").getFirst().displayName().equals("测试歌曲"),
                        "the song name did not survive reopening the cache");
                require(reopened.lookup(identity).rate() == 48000, "JEMA cache was unreadable after reopening");
            }
            cache.clear(identity.digest());
            require(cache.lookup(identity) == null && !Files.exists(rough.path()), "clearing a track must clear both versions");
            require(cache.catalog(null, "").isEmpty(), "the catalog did not reflect deletion");
            entry = cache.publish(identity, 48000, cacheFrames, raw, null, directory, compressedBudget, false);
            require(entry.path().toString().endsWith(".jema") && Files.size(entry.path()) < compressedBudget,
                    "the cache limit must use compressed bytes");
            int start = 8192 - 13;
            var window = cache.readWindow(entry, start, 100);
            require(window.covers(start, 100) && !window.covers(start - 1, 100), "window seek bounds");
            for (int lane = 0; lane < window.samples().length; lane++) {
                int offset = (start * StemCache.FLOATS_PER_FRAME + lane) * Float.BYTES;
                require(Float.floatToRawIntBits(window.samples()[lane]) == data.getInt(offset),
                        "PCM bits changed across a JEMA block boundary");
            }
            require(entry.peak(5) == .125f, "JEMA metadata lost the stem peak");
            require(cache.readWindow(entry, cacheFrames - 12, 12).end() == cacheFrames,
                    "the partial final JEMA block could not be read");

            Path oldPath = Files.write(entry.path().resolveSibling("a443a298-" + identity.digest() + ".stems"),
                    new byte[] {1, 2, 3});
            var bytes = Files.readAllBytes(entry.path());
            bytes[bytes.length - 1] ^= 1;
            Files.write(entry.path(), bytes);
            try {
                cache.lookup(identity);
                throw new AssertionError("corrupt JEMA metadata was accepted");
            } catch (IOException expected) {
                require(expected.getMessage() != null, "corruption reason missing");
            }
            bytes[bytes.length - 1] ^= 1;
            Files.write(entry.path(), bytes);
            long size = Files.size(entry.path());
            var otherIdentity = StemCache.Identity.read(other);
            Path limited = Files.createDirectory(directory.resolve("over-budget"));
            try {
                cache.publish(otherIdentity, 48000, cacheFrames, raw, null, limited, size - 1, false);
                throw new AssertionError("oversized JEMA cache was published");
            } catch (IOException expected) {
                require(Files.isRegularFile(entry.path()) && Files.isRegularFile(oldPath)
                        && cache.lookup(otherIdentity) == null, "failed publishing evicted valid caches");
            }
            var second = cache.publish(otherIdentity, 48000, cacheFrames, raw, null, directory, size, false);
            require(cache.size() == size && cache.lookup(identity) == null && Files.isRegularFile(oldPath),
                    "eviction did not drop the older JEMA while leaving unsupported files alone");
            bytes = Files.readAllBytes(second.path());
            // The revision 1 header and six channel-count fields occupy the first 60 bytes.
            bytes[60] ^= 1;
            Files.write(second.path(), bytes);
            cache.lookup(otherIdentity);
            try {
                cache.readWindow(second, 0, 128);
                throw new AssertionError("block corruption accepted");
            } catch (IOException expected) {
                require(expected.getMessage() != null, "corruption reason missing");
            }
            require(cache.readWindow(second, cacheFrames - 12, 12).end() == cacheFrames,
                    "a corrupt block invalidated an independent seek");
            Files.writeString(source, "changed source");
            require(!identity.current(), "source rewrite not recognized");
            require(!identity.digest().equals(StemCache.Identity.read(source).digest()), "source checksum did not change");
        } finally {
            WindowsRecycle.recycle(directory);
        }
    }

    private static void cacheMigrationChecks() throws Exception {
        Path work = Files.createTempDirectory("spw-track-mix-migration-check-").toRealPath();
        try {
            Path data = Files.createDirectory(work.resolve("app"));
            Path defaultCache = Files.createDirectory(data.resolve("cache"));
            Path choice = Files.createDirectory(work.resolve("新缓存"));
            Path destination = Files.createDirectory(choice.resolve(TrackMixOptions.CACHE_FOLDER));
            var identity = StemCache.Identity.read(Files.writeString(work.resolve("song"), "song source"));
            int frames = 37;
            var samples = ByteBuffer.allocate(frames * StemCache.BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN);
            while (samples.hasRemaining()) samples.putFloat(.125f);
            Path pcm = Files.write(work.resolve("six.f32"), samples.array());
            String title = "夜曲:现场/混音";
            Path named;
            try (var cache = new StemCache(defaultCache, WindowsRecycle::recycle)) {
                var entry = cache.publish(identity, title, 44100, frames, pcm, null, work, Long.MAX_VALUE, false);
                named = entry.path();
                require(named.getFileName().toString().startsWith("夜曲 现场 混音 - 标准分离 [")
                        && cache.lookup(identity).path().equals(named), "readable filenames lost the lookup key");
                cache.catalog(identity.digest(), title);
            }

            var otherName = new CacheFileName(null, CacheFileName.CURRENT_MODEL, true, "b".repeat(64));
            Path conflict = Files.writeString(defaultCache.resolve(otherName.forTitle("原版本")), "original");
            Path existing = Files.writeString(destination.resolve(otherName.forTitle("目标版本")), "existing");
            Path oldStems = Files.writeString(defaultCache.resolve(otherName.key() + ".stems"), "unsupported");

            try (var otherFs = FileSystems.newFileSystem(work.resolve("copy-target.zip"), Map.of("create", "true"))) {
                Path copied = otherFs.getPath("/");
                var copy = CacheDirectoryMigration.prepare(defaultCache, copied, new PythonEngine.Task((stage, progress) -> {}));
                require(copy.transferred().size() == 2 && Files.isRegularFile(named)
                        && Files.mismatch(named, copied.resolve(named.getFileName().toString())) == -1,
                        "copying across filesystems failed before commit");
            }

            try (var session = new TrackMixSession(data, () -> null)) {
                session.start();
                var switched = changeDirectory(session, destination);
                require(switched.success() && switched.message().contains("1 个")
                        && session.cacheDirectory().equals(destination), "switch did not report the filename conflict");
                require(!Files.exists(named) && Files.readString(conflict).equals("original")
                        && Files.readString(existing).equals("existing") && Files.isRegularFile(oldStems),
                        "migration lost a conflict or touched an unsupported file");
                try (var cache = new StemCache(destination, data.resolve("codec"), WindowsRecycle::recycle)) {
                    var entry = cache.lookup(identity);
                    near(cache.readWindow(entry, 1, 3).samples()[0], .125f, "migrated PCM");
                    require(cache.catalog(null, "").stream().anyMatch(track -> track.displayName().equals(title)),
                            "the complete song title was lost during migration");
                }
                try (var reopened = new TrackMixSession(data, () -> null)) {
                    require(reopened.cacheDirectory().equals(destination), "the selected cache directory was not saved");
                }
                var reset = changeDirectory(session, null);
                require(reset.success() && session.cacheDirectory().equals(defaultCache) && Files.isRegularFile(named)
                        && !Files.exists(destination.resolve(named.getFileName())),
                        "reset did not migrate the cache back to the default directory");
            }
        } finally {
            WindowsRecycle.recycle(work);
        }
    }

    private static TrackMixJobs.Result changeDirectory(TrackMixSession session, Path directory) throws Exception {
        var completion = new CompletableFuture<TrackMixJobs.Result>();
        require(session.changeCacheDirectory(directory, completion::complete), "cache migration was not queued");
        return completion.get(5, TimeUnit.SECONDS);
    }

    private static void playbackSessionChecks() throws Exception {
        Path directory = Files.createTempDirectory("spw-track-mix-session-check-").toAbsolutePath().normalize();
        try {
            Path config = Files.writeString(directory.resolve("settings.properties"),
                    "cacheGiB=23\nquality=true\ncpu=true\npreset=VOCALS\nsolo=8\nmute=1\ntrack.3=65\n");
            Path firstPath = Files.writeString(directory.resolve("first"), "test audio source");
            Path secondPath = Files.writeString(directory.resolve("second"), "second test audio source");
            var first = new TrackMixSession.Track(firstPath, "First", "a");
            var second = new TrackMixSession.Track(secondPath, "Second", "b");
            var selected = new AtomicReference<>(first);
            var cachedDigest = StemCache.Identity.read(secondPath).digest();
            Path cacheDir = Files.createDirectory(directory.resolve("cache"));
            Path cached = Files.write(cacheDir.resolve("9abcdef0-" + cachedDigest + ".jema"), new byte[1536]);
            Path orphan = Files.createFile(cacheDir.resolve("9abcdef0-quick-" + "a".repeat(64) + ".jema"));
            Path assets = Files.createDirectory(directory.resolve("models"));
            Path model = Files.write(assets.resolve("BS-Rofo-SW-Fixed.ckpt.part"), new byte[32]);
            var mix = MixSettings.DEFAULT.withVolume(3, 65).withMuted(1).withSolo(3);
            var preferences = new TrackMixOptions(24, true, true, true);
            try (var session = new TrackMixSession(directory, selected::get)) {
                require(session.options().equals(new TrackMixOptions(23, true, true, false)),
                        "legacy mix did not preserve cache and inference preferences");
                session.start();
                requireDefault(awaitTrack(session, first).mix(), "fresh startup with an old preset");
                session.setMix(ignored -> mix);
                require(session.status().mix() == mix, "mixer settings were not applied to the current track");

                var retitled = new TrackMixSession.Track(firstPath, "Retitled", "a");
                selected.set(retitled);
                require(awaitTrack(session, retitled).mix() == mix, "metadata refresh reset the same track");
                selected.set(second);
                requireDefault(awaitTrack(session, second).mix(), "switch to the second track");
                session.setMix(ignored -> mix);
                selected.set(first);
                requireDefault(awaitTrack(session, first).mix(), "return to the first track");

                session.setMix(ignored -> mix);
                var anotherItem = new TrackMixSession.Track(firstPath, "Another queue item", "c");
                selected.set(anotherItem);
                requireDefault(awaitTrack(session, anotherItem).mix(), "switch between IDs that share a file");

                session.setMix(ignored -> mix);
                try (var link = new TrackMixLink(session)) {
                    checkAmllPanel(anotherItem, preferences, session, cachedDigest, cached, orphan, model);
                }
            }

            var saved = new Properties();
            try (var stream = Files.newInputStream(config)) {
                saved.load(stream);
            }
            require(saved.size() == 4 && !saved.containsKey("preset"), "configuration still contains mixer state");
            try (var restarted = new TrackMixSession(directory, selected::get)) {
                require(restarted.options().equals(preferences), "cache or inference preferences were lost");
                requireDefault(restarted.status().mix(), "restart after editing the mixer");
            }
        } finally {
            WindowsRecycle.recycle(directory);
        }
    }

    private static TrackMixSession.Status awaitTrack(TrackMixSession session, TrackMixSession.Track expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var status = session.status();
            if (status.selection() != null && expected.equals(status.selection().track())
                    && status.selection().identity() != null) {
                return status;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("test song did not become current: " + expected.title());
    }

    private static void requireDefault(MixSettings mix, String context) {
        require(mix.preset == MixSettings.Preset.ORIGINAL && mix.muteMask() == 0 && mix.soloMask() == 0, context);
        for (int lane = 0; lane < MixSettings.STEMS; lane++) {
            require(mix.effectiveVolume(lane) == 100, context + ": a fader retained its previous value");
        }
    }

    private static void checkAmllPanel(TrackMixSession.Track track, TrackMixOptions preferences, TrackMixSession session,
                                       String cachedDigest, Path cached, Path orphan, Path model) throws Exception {
        var server = ManagementFactory.getPlatformMBeanServer();
        var name = new ObjectName("io.github.gaboron.amllspw:type=PlayerPanel,version=1,provider=dev.gaboron.spwtrackmix");
        var spectrum = new ObjectName("io.github.gaboron.amllspw:type=SpectrumSource,version=1,provider=dev.gaboron.spwtrackmix");
        var context = Map.of("trackId", track.id(), "path", track.path().toString());
        var render = new String[]{"java.lang.String", "java.util.Map"};
        var edit = new String[]{"java.lang.String", "java.util.Map", "java.lang.String", "int"};
        var descriptor = (Map<?, ?>) server.getAttribute(name, "Descriptor");
        require(descriptor.get("label").equals("六轨混音") && ((String) descriptor.get("svg")).contains("<svg"),
                "the generic toolbar contribution did not include its icon");
        var main = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        require(main.stream().anyMatch(row -> "quick".equals(((Map<?, ?>) row).get("action"))),
                "generic panel did not offer quick separation for the selected track");
        var gear = findControl(main, "action", "settings");
        require(gear != null && "chip".equals(gear.get("style")) && ((String) gear.get("svg")).contains("<circle"),
                "mixer header did not expose the settings icon");
        var stale = (List<?>) server.invoke(name, "render", new Object[]{"fixture",
                Map.of("trackId", "previous", "path", track.path().toString())}, render);
        require(stale.stream().noneMatch(row -> "quick".equals(((Map<?, ?>) row).get("action"))),
                "a stale host selection exposed actions for a different track");

        server.invoke(name, "action", new Object[]{"fixture", context, "settings", 0}, edit);
        var settings = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        require(findControl(settings, "text", "未就绪 · 32 B") != null
                && findControl(settings, "action", "chooseCacheDirectory") != null
                && findControl(settings, "action", "resetCacheDirectory") != null
                && findControl(settings, "action", "deleteAll") == null,
                "settings should show the model's disk usage and a single entry to manage the cache");
        server.invoke(name, "action", new Object[]{"fixture", context, "manageCache", 0}, edit);
        var cache = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        var buttons = findControl(cache, "id", "cache-actions");
        require(buttons != null && Integer.valueOf(8).equals(buttons.get("gap"))
                && findControl((List<?>) buttons.get("children"), "action", "backFromCache") != null
                && findControl((List<?>) buttons.get("children"), "action", "deleteAll") != null,
                "cache actions must share a row with spacing");
        require(findControl(cache, "text", "2 首 · 已占用 1.5 KiB") != null
                && findControl(cache, "text", "标准分离 · 1.5 KiB") != null
                && findControl(cache, "action", "delete." + cachedDigest) != null,
                "cache manager did not show the non-playing song and its size");
        require(findControl(cache, "text", "Second") != null, "playing a song did not label the matching legacy cache");
        server.invoke(name, "action", new Object[]{"fixture", context, "delete." + cachedDigest, 0}, edit);
        var pending = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        require(Files.isRegularFile(cached) && findControl(pending, "action", "confirmCache") != null,
                "the cache entry must remain on disk until confirmed");
        server.invoke(name, "action", new Object[]{"fixture", context, "cancelCache", 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "confirmCache", 0}, edit);
        require(Files.isRegularFile(cached), "cancelled deletion must not remove the cache");
        server.invoke(name, "action", new Object[]{"fixture", context, "delete." + cachedDigest, 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "confirmCache", 0}, edit);
        awaitStorage(session, 1);
        var remaining = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        require(!Files.exists(cached) && Files.isRegularFile(orphan)
                && findControl(remaining, "text", "1 首 · 已占用 0 B") != null,
                "deleting a song removed or counted the wrong cache files");
        server.invoke(name, "action", new Object[]{"fixture", context, "deleteAll", 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "confirmCache", 0}, edit);
        awaitStorage(session, 0);
        var empty = (List<?>) server.invoke(name, "render", new Object[]{"fixture", context}, render);
        require(findControl(empty, "text", "暂无缓存，已占用 0 B") != null && !Files.exists(orphan)
                && Files.isRegularFile(track.path()) && Files.isRegularFile(model),
                "deletion must update the total, preserve the source, and preserve the model");
        require(findControl(empty, "id", "job-notice") != null, "the open panel did not show its completion");
        var reopened = (List<?>) server.invoke(name, "render", new Object[]{"reopened", context}, render);
        require(findControl(reopened, "id", "job-notice") == null, "a reopened panel showed an earlier completion");
        server.invoke(name, "close", new Object[]{"reopened"}, new String[]{"java.lang.String"});
        server.invoke(name, "action", new Object[]{"fixture", context, "backFromCache", 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "cacheGiB", preferences.cacheGiB()}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "cpu", 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "fp16", 0}, edit);
        server.invoke(name, "action", new Object[]{"fixture", context, "cpu", 0}, edit);
        require(session.options().cacheGiB() == 23, "an uncommitted panel draft changed settings");
        server.invoke(name, "action", new Object[]{"fixture", context, "save", 0}, edit);
        require(session.options().equals(preferences), "the generic panel did not persist the requested preferences");
        server.invoke(name, "close", new Object[]{"fixture"}, new String[]{"java.lang.String"});
        require(server.invoke(spectrum, "spectrum", new Object[]{track.path().toString(), 0L},
                new String[]{"java.lang.String", "long"}) == null, "inactive spectrum must fall back to original PCM");
    }

    private static void awaitStorage(TrackMixSession session, int tracks) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (session.cacheCatalog().tracks().size() == tracks && session.status().queue().items().isEmpty()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("cache maintenance did not finish with " + tracks + " cached tracks");
    }

    private static Map<?, ?> findControl(List<?> controls, String property, String expected) {
        for (Object item : controls) {
            var control = (Map<?, ?>) item;
            if (expected.equals(control.get(property))) return control;
            if (control.get("children") instanceof List<?> children) {
                var match = findControl(children, property, expected);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static void queueChecks() {
        var work = new ArrayDeque<Runnable>();
        var ran = new ArrayList<String>();
        var clock = new AtomicLong();
        try (var jobs = new TrackMixJobs(work::addLast, () -> {}, clock::get)) {
            jobs.separate("First song", "a", false, task -> {
                ran.add("a");
                task.report("gpu32", .48);
                var view = jobs.snapshot("b");
                require(view.currentQueued() && view.items().size() == 2, "new song is not queued");
                var active = view.items().getFirst();
                require(active.title().equals("First song") && active.running()
                        && active.progress().fraction() == .48, "switching tracks changed the active job label or progress");
                return "finished";
            });
            jobs.separate("Second song", "b", true, task -> {
                ran.add("b");
                return "finished";
            });
            jobs.separate("Duplicate of second song", "b", false, task -> {
                throw new AssertionError("duplicate job ran");
            });
            jobs.separate("Third song", "c", false, task -> {
                throw new AssertionError("cancelled queued job ran");
            });
            var initial = jobs.snapshot("c");
            require(initial.items().size() == 3 && !initial.items().get(1).running()
                    && initial.items().get(1).kind() == TrackMixJobs.Kind.QUICK, "queue order or kind lost");
            jobs.cancel(initial.items().getLast().id());
            require(!jobs.snapshot("c").currentQueued(), "queued cancellation was ignored");

            work.removeFirst().run();
            var next = jobs.snapshot("b");
            require(next.items().size() == 1 && next.items().getFirst().title().equals("Second song")
                    && next.items().getFirst().running(), "next song was not promoted");
            require(next.notice().startsWith("First song"), "completion notice used a different song title");
            work.removeFirst().run();
            require(ran.equals(List.of("a", "b")) && jobs.snapshot("b").items().isEmpty(), "queue did not drain");

            jobs.separate("Never started", "a", true, task -> {
                throw new AssertionError("cancelled active job ran");
            });
            jobs.cancel(jobs.snapshot("a").items().getFirst().id());
            work.removeFirst().run();
            require(jobs.snapshot("a").notice().contains("已取消"), "active cancellation did not finish");
            clock.set(TimeUnit.SECONDS.toNanos(8));
            require(jobs.snapshot("a").notice().isEmpty(), "a job notice outlived its display duration");
        }
    }

    private static void fill(MemorySegment memory, float value) {
        for (long index = 0; index < memory.byteSize() / Float.BYTES; index++) {
            memory.setAtIndex(FLOAT, index, value);
        }
    }

    static void near(float actual, float expected, String label) {
        require(Math.abs(actual - expected) <= 3.1e-5f, label + ": expected " + expected + ", actual " + actual);
    }

    static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}

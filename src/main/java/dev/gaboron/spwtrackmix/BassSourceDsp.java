package dev.gaboron.spwtrackmix;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

final class BassSourceDsp implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BassSourceDsp.class.getName());
    private static final Registration[] NONE = {};
    private static final AtomicLong NEXT_CONTEXT = new AtomicLong();
    private static final ConcurrentHashMap<Long, Registration> CALLBACKS = new ConcurrentHashMap<>();
    // The host already uses JNA on BASS-owned threads. Keep the callback strongly
    // reachable for the plugin class loader and retire only the per-source context.
    private static final DspCallback CALLBACK = BassSourceDsp::onNativeBlock;
    private static final MemorySegment CALLBACK_ENTRY = callbackEntry();

    public interface DspCallback extends Callback {
        void callback(int effect, int channel, Pointer buffer, int length, Pointer context);
    }

    private static MemorySegment callbackEntry() {
        Native.setCallbackThreadInitializer(CALLBACK, new CallbackThreadInitializer(true, false, "Track Mix DSP"));
        return MemorySegment.ofAddress(Pointer.nativeValue(CallbackReference.getFunctionPointer(CALLBACK)));
    }

    private static void onNativeBlock(int effect, int channel, Pointer buffer, int length, Pointer context) {
        Registration registration = null;
        try {
            registration = CALLBACKS.get(Pointer.nativeValue(context));
            if (registration != null) {
                registration.onBlock(channel, Pointer.nativeValue(buffer), length);
            }
        } catch (Throwable error) {
            if (registration != null) {
                registration.bypassed = true;
            }
        }
    }

    private final BassLibrary library;
    private final StemCache cache;
    private final Function<StemCache.Entry, MixSettings> settings;
    private final Supplier<StemCache.Entry> admitted;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile Registration[] forPrefetch = NONE;
    private boolean closed;

    BassSourceDsp(BassLibrary library, StemCache cache, Function<StemCache.Entry, MixSettings> settings,
                  Supplier<StemCache.Entry> admitted) {
        this.library = library;
        this.cache = cache;
        this.settings = settings;
        this.admitted = admitted;
    }

    synchronized boolean attach(int channel, Path path) throws Throwable {
        if (closed) {
            return false;
        }
        for (var iterator = registrations.iterator(); iterator.hasNext();) {
            Registration registration = iterator.next();
            if (registration.released) {
                iterator.remove();
            } else if (registration.channel == channel) {
                return false;
            }
        }

        AudioFormat format = library.format(channel);
        if (format == null) {
            return false;
        }

        long position = library.sourceBytePosition(channel);
        Registration registration = new Registration(channel, path, format,
                (int) Math.clamp(position / format.frameBytes(), 0, format.frames() - 1));
        CALLBACKS.put(registration.context, registration);
        registrations.add(registration);
        try {
            registration.dsp = library.addDsp(channel, CALLBACK_ENTRY, MemorySegment.ofAddress(registration.context));
            return registration.dsp != 0 && !registration.released;
        } finally {
            if (registration.dsp == 0 || registration.released) {
                registration.retire();
                registrations.remove(registration);
            }
            forPrefetch = registrations.toArray(Registration[]::new);
        }
    }

    void prefetch(Path path, StemCache.Entry entry) throws IOException {
        for (Registration registration : forPrefetch) {
            if (registration.released || entry == null || !registration.path.equals(path)
                    || !registration.format.matches(entry)) {
                registration.clearWindows();
            } else {
                registration.loadWindow(entry);
            }
        }
    }


    Object[] spectrum(Path path, long positionMs) {
        var entry = admitted.get();
        if (entry == null || settings.apply(entry).original()) {
            return null;
        }
        for (Registration registration : forPrefetch) {
            if (registration.selected == entry && registration.path.equals(path)
                    && !registration.released && !registration.bypassed) {
                var tap = registration.spectrum;
                return tap == null ? new Object[]{entry.rate(), new float[0], new float[0]} : tap.at(positionMs);
            }
        }
        return null;
    }

    boolean ready(Path path, StemCache.Entry entry) {
        if (entry == null) return false;
        for (Registration registration : forPrefetch) {
            if (registration.selected == entry && registration.path.equals(path)
                    && !registration.released && !registration.bypassed) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        closed = true;
        forPrefetch = NONE;
        for (Registration registration : registrations) {
            registration.bypassed = true;
        }
        for (Registration registration : registrations) {
            try {
                if (!registration.released && !library.freeDsp(registration.dsp) && !registration.released) {
                    LOG.log(System.Logger.Level.WARNING, "BASS DSP is bypassed pending native release");
                }
            } catch (Throwable error) {
                LOG.log(System.Logger.Level.WARNING, "Could not release the bypassed BASS DSP", error);
            } finally {
                registration.retire();
            }
        }
        registrations.clear();
    }

    private final class Registration {
        private final int channel;
        private final Path path;
        private final AudioFormat format;
        private final RealtimeStemMixer mixer;
        private final long context = NEXT_CONTEXT.incrementAndGet();
        private int dsp;
        private volatile boolean released;
        private volatile boolean bypassed;
        private volatile int decodedFrame;
        private volatile StemCache.Window window;
        private volatile StemCache.Window openingWindow;
        private volatile StemCache.Entry selected;
        private volatile MixedSpectrum spectrum;

        private Registration(int channel, Path path, AudioFormat format, int decodedFrame) {
            this.channel = channel;
            this.path = path;
            this.format = format;
            this.decodedFrame = decodedFrame;
            mixer = new RealtimeStemMixer(format.rate());
        }

        private void clearWindows() {
            selected = null;
            window = null;
            openingWindow = null;
            spectrum = null;
        }

        private void loadWindow(StemCache.Entry entry) throws IOException {
            int frame = decodedFrame;
            int ahead = usableAhead(entry, frame);
            if (covers(openingWindow, entry, frame, ahead)) {
                window = openingWindow;
            } else if (!covers(window, entry, frame, ahead) && frame < entry.frames()) {
                window = readWindow(entry, frame);
            }
            if (openingWindow == null || openingWindow.entry() != entry) {
                openingWindow = frame == 0 && covers(window, entry, 0, ahead) ? window
                        : cache.readWindow(entry, 0,
                                Math.min(entry.frames(), Math.min(entry.rate(), StemCache.MAX_WINDOW_FRAMES)));
            }
            if (selected != entry) {
                spectrum = new MixedSpectrum(entry.rate());
                selected = entry;
            }
        }

        private int usableAhead(StemCache.Entry entry, int frame) {
            return Math.min(entry.frames() - frame, Math.min(format.rate() / 3, 40_000));
        }

        private StemCache.Window readWindow(StemCache.Entry entry, int frame) throws IOException {
            int start = Math.max(0, frame - Math.min(format.rate() / 4, 24_000));
            int count = Math.min(entry.frames() - start, Math.min(format.rate() * 2, StemCache.MAX_WINDOW_FRAMES));
            return cache.readWindow(entry, start, count);
        }

        private boolean covers(StemCache.Window candidate, StemCache.Entry entry, long frame, int frames) {
            return candidate != null && candidate.entry() == entry && candidate.covers(frame, frames);
        }

        private StemCache.Window windowAt(StemCache.Entry entry, long frame, int frames) {
            var ready = window;
            if (covers(ready, entry, frame, frames)) {
                return ready;
            }

            var beginning = openingWindow;
            return covers(beginning, entry, frame, frames) ? beginning : null;
        }

        private void onBlock(int source, long buffer, int length) throws Throwable {
            // A null buffer indicates that BASS has removed the DSP or freed the source.
            if (buffer == 0) {
                retire();
                return;
            }
            if (source != channel || released || bypassed
                    || length <= 0 || length % AudioFormat.FLOAT_FRAME_BYTES != 0) {
                return;
            }
            long position = library.dspBytePosition(channel);
            if (position < 0 || position % format.frameBytes() != 0) {
                return;
            }
            long frame = position / format.frameBytes();
            if (frame >= format.frames()) {
                return;
            }
            decodedFrame = (int) frame;
            int frames = length / AudioFormat.FLOAT_FRAME_BYTES;
            StemCache.Entry entry = admitted.get();
            if (entry != selected) {
                entry = null;
            }
            var pcm = MemorySegment.ofAddress(buffer).reinterpret(length);
            if (mixer.process(pcm, frame, frames, entry, windowAt(entry, frame, frames), settings.apply(entry))) {
                var tap = spectrum;
                if (tap != null) {
                    tap.write(pcm, (int) frame, frames);
                }
            }
        }

        private void retire() {
            released = true;
            CALLBACKS.remove(context, this);
            clearWindows();
        }
    }
}

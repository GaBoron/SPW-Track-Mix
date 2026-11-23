package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class BassSourceDsp implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BassSourceDsp.class.getName());
    private static final Registration[] NONE = {};
    private static final AtomicLong NEXT_CONTEXT = new AtomicLong();
    private static final ConcurrentHashMap<Long, Registration> CALLBACKS = new ConcurrentHashMap<>();
    // A free notification runs inside the native trampoline. Keep one trampoline alive
    // for the process and retire each registration by its non-reused context token.
    private static final MemorySegment CALLBACK = createCallback();

    private static MemorySegment createCallback() {
        try {
            var target = MethodHandles.lookup().findStatic(BassSourceDsp.class, "onNativeBlock",
                    BassLibrary.DSP_PROC.toMethodType());
            return Linker.nativeLinker().upcallStub(target, BassLibrary.DSP_PROC, Arena.global());
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void onNativeBlock(int effect, int channel, MemorySegment buffer, int length, MemorySegment context) {
        Registration registration = null;
        try {
            registration = CALLBACKS.get(context.address());
            if (registration != null) {
                registration.onBlock(channel, buffer, length);
            }
        } catch (Throwable error) {
            // Throwing through an FFM upcall terminates the JVM.
            if (registration != null) {
                registration.bypassed = true;
            }
        }
    }

    private final BassLibrary library;
    private final Supplier<MixSettings> settings;
    private final Supplier<StemCache.Entry> admitted;
    private final List<Registration> registrations = new ArrayList<>();
    private volatile Registration[] forPrefetch = NONE;
    private boolean closed;

    BassSourceDsp(BassLibrary library, Supplier<MixSettings> settings, Supplier<StemCache.Entry> admitted) {
        this.library = library;
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

        Registration registration = new Registration(channel, path, format);
        CALLBACKS.put(registration.context, registration);
        registrations.add(registration);
        try {
            registration.dsp = library.addDsp(channel, CALLBACK, MemorySegment.ofAddress(registration.context));
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

    boolean prepareSeek(int channel, long bytePosition) {
        StemCache.Entry current = admitted.get();
        if (current == null || settings.get().original()) {
            return false;
        }
        for (Registration registration : forPrefetch) {
            if (registration.channel == channel && !registration.released
                    && registration.selected == current) {
                long frame = bytePosition / registration.format.frameBytes();
                if (bytePosition >= 0 && frame < current.frames()) {
                    registration.requestedFrame.set((int) frame);
                    return true;
                }
            }
        }
        return false;
    }

    Object[] spectrum(Path path, long positionMs) {
        var entry = admitted.get();
        if (entry == null || settings.get().original()) {
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

    boolean attached(Path path, StemCache.Entry entry) {
        for (Registration registration : forPrefetch) {
            if (!registration.released && !registration.bypassed && registration.path.equals(path)
                    && (entry == null || registration.format.matches(entry))) {
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
        private volatile StemCache.Window seekWindow;
        private volatile StemCache.Entry selected;
        private volatile MixedSpectrum spectrum;
        private final AtomicInteger requestedFrame = new AtomicInteger(-1);

        private Registration(int channel, Path path, AudioFormat format) {
            this.channel = channel;
            this.path = path;
            this.format = format;
            mixer = new RealtimeStemMixer(format.rate());
        }

        private void clearWindows() {
            window = null;
            openingWindow = null;
            seekWindow = null;
            selected = null;
            spectrum = null;
            requestedFrame.set(-1);
        }

        private void loadWindow(StemCache.Entry entry) throws IOException {
            if (selected != entry || spectrum == null) {
                spectrum = new MixedSpectrum(entry.rate());
            }
            selected = entry;
            int request = requestedFrame.getAndSet(-1);
            if (request >= 0) {
                if (!covers(window, entry, request, usableAhead(entry, request))
                        && !covers(openingWindow, entry, request, usableAhead(entry, request))
                        && !covers(seekWindow, entry, request, usableAhead(entry, request))) {
                    seekWindow = readWindow(entry, request);
                }
            }
            int frame = decodedFrame;
            int ahead = usableAhead(entry, frame);
            if (covers(seekWindow, entry, frame, ahead)) {
                window = seekWindow;
            } else if (covers(openingWindow, entry, frame, ahead)) {
                window = openingWindow;
            } else if (!covers(window, entry, frame, ahead) && frame < entry.frames()) {
                window = readWindow(entry, frame);
            }
            if (openingWindow == null || openingWindow.entry() != entry) {
                openingWindow = frame == 0 && covers(window, entry, 0, ahead) ? window
                        : StemCache.readWindow(entry, 0, Math.min(entry.frames(), entry.rate()));
            }
        }

        private int usableAhead(StemCache.Entry entry, int frame) {
            return Math.min(entry.frames() - frame, Math.min(format.rate() / 3, 40_000));
        }

        private StemCache.Window readWindow(StemCache.Entry entry, int frame) throws IOException {
            int start = Math.max(0, frame - Math.min(format.rate() / 4, 24_000));
            int count = Math.min(entry.frames() - start, Math.min(format.rate() * 2, 192_000));
            return StemCache.readWindow(entry, start, count);
        }

        private boolean covers(StemCache.Window candidate, StemCache.Entry entry, long frame, int frames) {
            return candidate != null && candidate.entry() == entry && candidate.covers(frame, frames);
        }

        private StemCache.Window windowAt(StemCache.Entry entry, long frame, int frames) {
            var ready = window;
            if (covers(ready, entry, frame, frames)) {
                return ready;
            }
            var seek = seekWindow;
            if (covers(seek, entry, frame, frames)) {
                return seek;
            }
            var beginning = openingWindow;
            return covers(beginning, entry, frame, frames) ? beginning : null;
        }

        private void onBlock(int source, MemorySegment buffer, int length) throws Throwable {
            // A null buffer indicates that BASS has removed the DSP or freed the source.
            if (buffer.address() == 0) {
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
            var pcm = buffer.reinterpret(length);
            if (mixer.process(pcm, frame, frames, entry, windowAt(entry, frame, frames), settings.get())) {
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

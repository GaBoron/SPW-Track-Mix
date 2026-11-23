package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class BassSourceDsp implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BassSourceDsp.class.getName());
    private static final Registration[] NONE = {};
    private static final MethodHandle CALLBACK;

    static {
        try {
            CALLBACK = MethodHandles.lookup().findVirtual(Registration.class, "onBlock",
                    MethodType.methodType(void.class, int.class, int.class,
                            MemorySegment.class, int.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
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
            if (registration.released && registration.closeArena()) {
                iterator.remove();
            } else if (!registration.released && registration.channel == channel) {
                return false;
            }
        }

        AudioFormat format = library.format(channel);
        if (format == null) {
            return false;
        }

        Registration registration = new Registration(channel, path, format);
        registrations.add(registration);
        try {
            registration.dsp = library.addDsp(channel, CALLBACK.bindTo(registration), registration.arena);
            return registration.dsp != 0;
        } finally {
            if (registration.dsp == 0 && registration.closeArena()) {
                registrations.remove(registration);
            }
            forPrefetch = registrations.toArray(Registration[]::new);
        }
    }

    void prefetch(Path path, StemCache.Entry entry) throws IOException {
        for (Registration registration : forPrefetch) {
            if (registration.released || entry == null || !registration.path.equals(path)
                    || !registration.format.matches(entry)) {
                registration.window = null;
            } else {
                registration.loadWindow(entry);
            }
        }
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
            registration.window = null;
        }
        for (var iterator = registrations.iterator(); iterator.hasNext();) {
            Registration registration = iterator.next();
            try {
                if (registration.released || library.freeDsp(registration.dsp)) {
                    if (registration.closeArena()) {
                        iterator.remove();
                    }
                } else {
                    LOG.log(System.Logger.Level.WARNING, "BASS DSP is bypassed pending native release");
                }
            } catch (Throwable error) {
                LOG.log(System.Logger.Level.WARNING, "Could not release the bypassed BASS DSP", error);
            }
        }
    }

    private final class Registration {
        private final int channel;
        private final Path path;
        private final AudioFormat format;
        private final RealtimeStemMixer mixer;
        private final Arena arena = Arena.ofShared();
        private int dsp;
        private volatile boolean released;
        private volatile boolean bypassed;
        private volatile int decodedFrame;
        private volatile StemCache.Window window;

        private Registration(int channel, Path path, AudioFormat format) {
            this.channel = channel;
            this.path = path;
            this.format = format;
            mixer = new RealtimeStemMixer(format.rate());
        }

        private void loadWindow(StemCache.Entry entry) throws IOException {
            int frame = decodedFrame;
            int ahead = Math.min(entry.frames() - frame, Math.min(format.rate() / 3, 40_000));
            var old = window;
            if (frame >= entry.frames() || (old != null && old.entry() == entry && old.covers(frame, ahead))) {
                return;
            }
            int start = Math.max(0, frame - Math.min(format.rate() / 4, 24_000));
            int count = Math.min(entry.frames() - start, Math.min(format.rate() * 2, 192_000));
            window = StemCache.readWindow(entry, start, count);
        }

        private void onBlock(int effect, int source, MemorySegment buffer, int length, MemorySegment user) {
            try {
                // BASS_DSP_FREECALL signals that this effect was detached or its source was freed.
                if (buffer.address() == 0) {
                    released = true;
                    window = null;
                    return;
                }
                if (bypassed || source != channel || length <= 0 || length % AudioFormat.FLOAT_FRAME_BYTES != 0) {
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
                StemCache.Window ready = window;
                if (ready != null && ready.entry() != admitted.get()) {
                    ready = null;
                }
                mixer.process(buffer.reinterpret(length), frame, length / AudioFormat.FLOAT_FRAME_BYTES,
                        ready, settings.get());
            } catch (Throwable error) {
                // Exceptions must never escape an FFM upcall on the host audio thread.
                bypassed = true;
            }
        }

        private boolean closeArena() {
            try {
                arena.close();
                return true;
            } catch (IllegalStateException busy) {
                // An in-flight upcall can keep this arena in use.
                return false;
            }
        }
    }
}

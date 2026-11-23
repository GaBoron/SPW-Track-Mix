package dev.gaboron.spwtrackmix;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

final class BassSourceDsp implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(BassSourceDsp.class.getName());
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
    private final List<Registration> registrations = new ArrayList<>();
    private boolean closed;

    BassSourceDsp(BassLibrary library) {
        this.library = library;
        ProbeSignal.prepare();
    }

    synchronized boolean attach(int channel) throws Throwable {
        for (var iterator = registrations.iterator(); iterator.hasNext();) {
            Registration registration = iterator.next();
            if (registration.released && registration.closeArena()) {
                iterator.remove();
            } else if (!registration.released && registration.channel == channel) {
                return false;
            }
        }

        int frameBytes = closed ? 0 : library.probeFrameBytes(channel);
        if (frameBytes == 0) {
            return false;
        }

        Registration registration = new Registration(channel, frameBytes);
        registrations.add(registration);
        try {
            registration.dsp = library.addDsp(channel, CALLBACK.bindTo(registration), registration.arena);
            return registration.dsp != 0;
        } finally {
            if (registration.dsp == 0 && registration.closeArena()) {
                registrations.remove(registration);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Registration registration : registrations) {
            registration.bypassed = true;
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
        private final int sourceFrameBytes;
        private final Arena arena = Arena.ofShared();
        private int dsp;
        private volatile boolean released;
        private volatile boolean bypassed;

        private Registration(int channel, int sourceFrameBytes) {
            this.channel = channel;
            this.sourceFrameBytes = sourceFrameBytes;
        }

        private void onBlock(int effect, int source, MemorySegment buffer, int length, MemorySegment user) {
            try {
                // BASS_DSP_FREECALL signals that this effect was detached or its source was freed.
                if (buffer.address() == 0) {
                    released = true;
                    return;
                }
                if (bypassed || source != channel || length <= 0 || length % ProbeSignal.FLOAT_FRAME_BYTES != 0) {
                    return;
                }

                long position = library.dspBytePosition(channel);
                if (position < 0 || position % sourceFrameBytes != 0) {
                    return;
                }
                long frame = position / sourceFrameBytes;
                if (frame < ProbeSignal.FRAME_COUNT) {
                    ProbeSignal.process(buffer.reinterpret(length), (int) frame,
                            length / ProbeSignal.FLOAT_FRAME_BYTES);
                }
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
                // An in-flight upcall can keep this arena in use. Preserve its callback until release.
                return false;
            }
        }
    }
}

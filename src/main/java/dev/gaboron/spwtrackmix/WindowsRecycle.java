package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

final class WindowsRecycle {
    private static final int FO_DELETE = 3;
    private static final short RECYCLE_OR_WARN = 0x4454;

    private WindowsRecycle() {}

    static void recycle(Path path) throws IOException {
        if (!path.isAbsolute() || path.getParent() == null || !path.equals(path.toRealPath())) {
            throw new IOException("Expected an absolute cache path");
        }
        try (var arena = Arena.ofConfined()) {
            MemorySegment source = arena.allocateFrom(path + "\0", StandardCharsets.UTF_16LE);
            // SHFILEOPSTRUCTW on Windows x64. The final nul comes from allocateFrom.
            MemorySegment request = arena.allocate(56, 8);
            request.set(JAVA_INT, 8, FO_DELETE);
            request.set(ADDRESS, 16, source);
            request.set(JAVA_SHORT, 32, RECYCLE_OR_WARN);
            int result = (int) Shell.FILE_OPERATION.invokeExact(request);
            if (result != 0 || request.get(JAVA_INT, 36) != 0) {
                throw new IOException("The Recycle Bin could not accept " + path + " (" + result + ")");
            }
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("The Recycle Bin is unavailable", error);
        }
    }

    private static final class Shell {
        private static final MethodHandle FILE_OPERATION = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("Shell32.dll", Arena.global()).findOrThrow("SHFileOperationW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }
}

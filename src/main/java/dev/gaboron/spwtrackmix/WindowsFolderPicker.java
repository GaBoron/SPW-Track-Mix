package dev.gaboron.spwtrackmix;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.nio.charset.StandardCharsets.UTF_16LE;

/** Windows Common Item Dialog in folder-picking mode. Call on a dedicated STA thread. */
final class WindowsFolderPicker {
    private static final int CANCELLED = 0x800704c7;
    private static final int PICK_FOLDERS = 0x20;
    private static final int FORCE_FILESYSTEM = 0x40;
    private static final int NO_CHANGE_DIR = 0x08;
    private static final int PATH_MUST_EXIST = 0x800;
    private static final int FILESYSTEM_PATH = 0x80058000;
    private static final String FILE_OPEN_CLASS = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
    private static final String FILE_OPEN_INTERFACE = "D57C7288-D4AD-4768-BE02-9D969532D960";
    private static final String SHELL_ITEM_INTERFACE = "43826D1E-E718-42EE-BC55-A1E261C37BFE";

    private WindowsFolderPicker() {}

    static Path choose(long owner, Path initialDirectory) throws Throwable {
        check((int) Native.INITIALIZE.invokeExact(MemorySegment.NULL, 0x2 | 0x4), "CoInitializeEx");
        try (var arena = Arena.ofConfined(); var dialog = createDialog(arena)) {
            configure(dialog, arena, initialDirectory);
            int shown = dialog.call(Slot.SHOW, MemorySegment.ofAddress(owner));
            if (shown == CANCELLED) return null;
            check(shown, "Show");
            var result = arena.allocate(ADDRESS);
            check(dialog.call(Slot.GET_RESULT, result), "GetResult");
            try (var item = new ComObject(result.get(ADDRESS, 0))) {
                var name = arena.allocate(ADDRESS);
                check(item.call(Slot.GET_DISPLAY_NAME, FILESYSTEM_PATH, name), "GetDisplayName");
                var path = name.get(ADDRESS, 0);
                try {
                    return Path.of(path.reinterpret(65_536).getString(0, UTF_16LE));
                } finally {
                    Native.FREE.invokeExact(path);
                }
            }
        } finally {
            Native.UNINITIALIZE.invokeExact();
        }
    }

    private static ComObject createDialog(Arena arena) throws Throwable {
        var result = arena.allocate(ADDRESS);
        check((int) Native.CREATE.invokeExact(guid(arena, FILE_OPEN_CLASS), MemorySegment.NULL, 1,
                guid(arena, FILE_OPEN_INTERFACE), result), "CoCreateInstance");
        return new ComObject(result.get(ADDRESS, 0));
    }

    private static void configure(ComObject dialog, Arena arena, Path initialDirectory) throws Throwable {
        var options = arena.allocate(JAVA_INT);
        check(dialog.call(Slot.GET_OPTIONS, options), "GetOptions");
        int flags = options.get(JAVA_INT, 0) | PICK_FOLDERS | FORCE_FILESYSTEM | NO_CHANGE_DIR | PATH_MUST_EXIST;
        check(dialog.call(Slot.SET_OPTIONS, flags), "SetOptions");
        check(dialog.call(Slot.SET_TITLE, arena.allocateFrom("选择音轨缓存目录", UTF_16LE)), "SetTitle");
        setInitialFolder(dialog, arena, initialDirectory);
    }

    private static void setInitialFolder(ComObject dialog, Arena arena, Path directory) throws Throwable {
        if (directory == null) return;
        var item = arena.allocate(ADDRESS);
        int status = (int) Native.PARSE_ITEM.invokeExact(arena.allocateFrom(directory.toString(), UTF_16LE),
                MemorySegment.NULL, guid(arena, SHELL_ITEM_INTERFACE), item);
        if (status >= 0) {
            try (var folder = new ComObject(item.get(ADDRESS, 0))) {
                check(dialog.call(Slot.SET_FOLDER, folder.pointer), "SetFolder");
            }
        }
    }

    private static MemorySegment guid(Arena arena, String value) {
        UUID uuid = UUID.fromString(value);
        long high = uuid.getMostSignificantBits();
        byte[] bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) (high >>> 32)).putShort((short) (high >>> 16)).putShort((short) high)
                .order(ByteOrder.BIG_ENDIAN).putLong(uuid.getLeastSignificantBits()).array();
        return arena.allocate(16, 4).copyFrom(MemorySegment.ofArray(bytes));
    }

    private static void check(int status, String operation) throws IOException {
        if (status < 0) throw new IOException(operation + " (HRESULT " + Integer.toHexString(status) + ")");
    }

    // IUnknown, IFileDialog and IShellItem vtable slots on Windows x64.
    private enum Slot {
        RELEASE(2), SHOW(3, ADDRESS), SET_OPTIONS(9, JAVA_INT), GET_OPTIONS(10, ADDRESS),
        SET_FOLDER(12, ADDRESS), SET_TITLE(17, ADDRESS), GET_RESULT(20, ADDRESS),
        GET_DISPLAY_NAME(5, JAVA_INT, ADDRESS);

        private final int index;
        private final FunctionDescriptor signature;

        Slot(int index, MemoryLayout... arguments) {
            this.index = index;
            var parameters = new MemoryLayout[arguments.length + 1];
            parameters[0] = ADDRESS;
            System.arraycopy(arguments, 0, parameters, 1, arguments.length);
            signature = FunctionDescriptor.of(JAVA_INT, parameters);
        }
    }

    private record ComObject(MemorySegment pointer) implements AutoCloseable {
        int call(Slot method, Object... arguments) throws Throwable {
            var table = pointer.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
            var function = table.reinterpret((method.index + 1) * ADDRESS.byteSize()).getAtIndex(ADDRESS, method.index);
            var parameters = new ArrayList<Object>(arguments.length + 1);
            parameters.add(pointer);
            parameters.addAll(Arrays.asList(arguments));
            return (int) Native.LINKER.downcallHandle(function, method.signature).invokeWithArguments(parameters);
        }

        @Override
        public void close() throws IOException {
            try {
                call(Slot.RELEASE);
            } catch (Throwable error) {
                throw new IOException("Could not release the Windows folder dialog", error);
            }
        }
    }

    private static final class Native {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup OLE = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
        private static final SymbolLookup SHELL = SymbolLookup.libraryLookup("shell32.dll", Arena.global());
        private static final MethodHandle INITIALIZE = LINKER.downcallHandle(
                OLE.findOrThrow("CoInitializeEx"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private static final MethodHandle UNINITIALIZE = LINKER.downcallHandle(
                OLE.findOrThrow("CoUninitialize"), FunctionDescriptor.ofVoid());
        private static final MethodHandle CREATE = LINKER.downcallHandle(OLE.findOrThrow("CoCreateInstance"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle PARSE_ITEM = LINKER.downcallHandle(
                SHELL.findOrThrow("SHCreateItemFromParsingName"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle FREE = LINKER.downcallHandle(
                OLE.findOrThrow("CoTaskMemFree"), FunctionDescriptor.ofVoid(ADDRESS));
    }
}

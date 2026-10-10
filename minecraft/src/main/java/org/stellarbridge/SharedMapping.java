package org.stellarbridge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file Minecraft exclusively creates and maps for the adapter to open (raw frames, poses): libc on Linux, Kernel32 on
 * Windows, both through Minecraft's own JNA. Views of one local file are coherent across processes on both systems; on
 * Windows only for a local file, never a network share.
 */
final class SharedMapping implements AutoCloseable {
    interface LibC extends Library {
        int open(String path, int flags, int mode);
        int ftruncate(int fd, NativeLong length);
        Pointer mmap(Pointer address, NativeLong length, int protection, int flags, int fd, NativeLong offset);
        int munmap(Pointer address, NativeLong length);
        int close(int fd);
    }
    // Loaded on first use, so Windows never looks for a "c" library (JNA maps that name to libc on Linux only).
    private static final class Linux { static final LibC C = Native.load("c", LibC.class); }

    /** Little-endian view of the whole file; never valid after close. */
    final ByteBuffer map;
    private final int bytes;
    private Pointer address;
    private int fd = -1;
    private WinNT.HANDLE file, mapping;

    static boolean supported() { return (Platform.isLinux() || Platform.isWindows()) && Native.POINTER_SIZE == 8; }

    /** Creates path (it must not exist, not even as a symlink) at bytes zeros and maps it; a failure removes what it created. */
    SharedMapping(Path path, int bytes, String what) throws IOException {
        if (!supported()) throw new IOException("The " + what + " requires 64-bit Linux or Windows");
        this.bytes = bytes;
        boolean created = false;
        try {
            if (Platform.isWindows()) {
                // Exclusive create without following a reparse point, not inheritable; read, write and delete sharing
                // so the adapter (shared_memory.hpp) opens it and either side may delete it.
                var k = Kernel32.INSTANCE;
                file = k.CreateFile(path.toString(), WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
                        WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null, WinNT.CREATE_NEW,
                        WinNT.FILE_ATTRIBUTE_NORMAL | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
                if (WinBase.INVALID_HANDLE_VALUE.equals(file)) {
                    file = null;
                    throw new IOException("Cannot exclusively create the " + what + ": error " + Native.getLastError());
                }
                created = true;
                // Sizing the mapping grows the file; NTFS reads the new bytes as zeros.
                mapping = k.CreateFileMapping(file, null, WinNT.PAGE_READWRITE, 0, bytes, null);
                if (mapping == null) throw new IOException("Cannot size the " + what + ": error " + Native.getLastError());
                address = k.MapViewOfFile(mapping, WinBase.FILE_MAP_WRITE, 0, 0, bytes);
                if (address == null) throw new IOException("Cannot map the " + what + ": error " + Native.getLastError());
            } else {
                fd = Linux.C.open(path.toString(), 2 | 64 | 128 | 0x20000 | 0x80000, 0600); // RDWR, CREAT, EXCL, NOFOLLOW, CLOEXEC.
                if (fd < 0) throw new IOException("Cannot exclusively create the " + what + ": errno " + Native.getLastError());
                created = true;
                if (Linux.C.ftruncate(fd, new NativeLong(bytes)) != 0) throw new IOException("Cannot size the " + what);
                address = Linux.C.mmap(null, new NativeLong(bytes), 3, 1, fd, new NativeLong(0)); // READ|WRITE, MAP_SHARED.
                if (Pointer.nativeValue(address) == -1) { address = null; throw new IOException("Cannot map the " + what); }
            }
            map = address.getByteBuffer(0, bytes).order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException | RuntimeException | Error failure) {
            close();
            if (created) Files.deleteIfExists(path);
            throw failure;
        }
    }

    /** Unmaps and closes; the file stays for its owner's release protocol to delete. Idempotent. */
    @Override public void close() {
        if (Platform.isWindows()) {
            var k = Kernel32.INSTANCE;
            if (address != null) k.UnmapViewOfFile(address);
            if (mapping != null) k.CloseHandle(mapping);
            if (file != null) k.CloseHandle(file);
            mapping = file = null;
        } else {
            if (address != null) Linux.C.munmap(address, new NativeLong(bytes));
            if (fd >= 0) Linux.C.close(fd);
            fd = -1;
        }
        address = null;
    }
}

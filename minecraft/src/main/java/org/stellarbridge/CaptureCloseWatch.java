package org.stellarbridge;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.platform.linux.ErrNo;
import com.sun.jna.platform.linux.Fcntl;
import com.sun.jna.platform.unix.LibCAPI.size_t;
import com.sun.jna.platform.unix.LibCAPI.ssize_t;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** One Linux observation epoch. File close is not completion of an Unreal task. */
final class CaptureCloseWatch implements AutoCloseable {
    static final int MODIFY = 0x2, CLOSE_WRITE = 0x8, MOVED_FROM = 0x40, MOVED_TO = 0x80,
            CREATE = 0x100, DELETE = 0x200, DELETE_SELF = 0x400, MOVE_SELF = 0x800,
            UNMOUNT = 0x2000, OVERFLOW = 0x4000, IGNORED = 0x8000, ISDIR = 0x40000000;
    private static final int INVALIDATION = DELETE_SELF | MOVE_SELF | UNMOUNT | OVERFLOW | IGNORED;
    private static final Pattern NAME = Pattern.compile("eve-frame-([0-9]{1,16}-[0-9]{1,16})-(?:color\\.bmp|mask\\.png|depth\\.hdr)");
    private enum State { PENDING, READY, INVALID }
    private final Path directory;
    private final Map<String, State> names = new HashMap<>();
    private final Function read, close;
    private Memory buffer;
    private int fd = -1, watch;
    private String failure;
    private String retiredThrough;

    CaptureCloseWatch(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        if (!Platform.isLinux()) throw new IOException("Asynchronous capture requires Linux close notifications");
        CaptureFrame.validateDirectory(this.directory);
        var libc = NativeLibrary.getInstance("c"); // Shared JNA library: only our fd and buffer are owned.
        read = libc.getFunction("read");
        close = libc.getFunction("close");
        try {
            fd = libc.getFunction("inotify_init1").invokeInt(new Object[] {Fcntl.O_NONBLOCK | Fcntl.O_CLOEXEC});
            if (fd < 0) throw new IOException("Cannot initialize capture close notifications: errno " + Native.getLastError());
            int mask = MODIFY | CLOSE_WRITE | MOVED_FROM | MOVED_TO | CREATE | DELETE
                    | DELETE_SELF | MOVE_SELF | UNMOUNT | 0x01000000 | 0x02000000; // ONLYDIR, DONT_FOLLOW.
            watch = libc.getFunction("inotify_add_watch", Function.C_CONVENTION, "UTF-8")
                    .invokeInt(new Object[] {fd, this.directory.toString(), mask});
            if (watch < 0) throw new IOException("Cannot watch capture directory: errno " + Native.getLastError());
            buffer = new Memory(16384);
        } catch (IOException | RuntimeException | Error exception) {
            close();
            throw exception;
        }
    }

    synchronized void available(Path directory) throws IOException {
        if (!this.directory.equals(directory.toAbsolutePath().normalize()))
            throw new IOException("Capture directory differs from its close-observation epoch");
        drain();
    }

    synchronized void requireReady(Path directory, String id) throws IOException {
        available(directory);
        for (String suffix : new String[] {"color.bmp", "mask.png", "depth.hdr"}) {
            String name = "eve-frame-" + id + "-" + suffix;
            if (names.get(name) != State.READY)
                throw new IOException("Asynchronous capture has no immutable close observation for " + suffix);
        }
    }

    synchronized void retire(Path directory, String id) throws IOException {
        requireReady(directory, id);
        if (retiredThrough != null && !CaptureFrame.newer(id, retiredThrough))
            throw new IOException("Capture generation was already retired");
        retiredThrough = id;
        names.keySet().removeIf(name -> {
            var match = NAME.matcher(name);
            return match.matches() && !CaptureFrame.newer(match.group(1), retiredThrough);
        });
    }

    synchronized void drain() throws IOException {
        check();
        // ponytail: bounded polling on existing callers; overflow or budget exhaustion aborts this epoch.
        for (int attempt = 0; attempt < 16; attempt++) {
            long count = ((ssize_t) read.invoke(ssize_t.class, new Object[] {fd, buffer, new size_t(buffer.size())})).longValue();
            if (count < 0) {
                int error = Native.getLastError();
                if (error == ErrNo.EAGAIN) return;
                if (error == ErrNo.EINTR) continue;
                invalidate("Capture close notification read failed: errno " + error);
                check();
            }
            if (count <= 0 || count > buffer.size()) {
                invalidate("Invalid capture close notification read");
                check();
            }
            for (long offset = 0; offset < count;) {
                if (count - offset < 16) {
                    invalidate("Truncated capture close notification");
                    check();
                }
                int wd = buffer.getInt(offset), mask = buffer.getInt(offset + 4), length = buffer.getInt(offset + 12);
                if (length < 0 || length > 256 || length > count - offset - 16) {
                    invalidate("Invalid capture close notification name");
                    check();
                }
                String name = "";
                if (length > 0) {
                    byte[] bytes = buffer.getByteArray(offset + 16, length);
                    int end = 0;
                    while (end < bytes.length && bytes[end] != 0) end++;
                    if (end == bytes.length) {
                        invalidate("Unterminated capture close notification name");
                        check();
                    }
                    name = new String(bytes, 0, end, StandardCharsets.US_ASCII);
                }
                if (wd == watch || (mask & OVERFLOW) != 0) event(mask, name);
                else invalidate("Capture close notification changed watch");
                check();
                offset += 16L + length;
            }
        }
        invalidate("Capture close notification budget exhausted");
        check();
    }

    synchronized void event(int mask, String name) {
        if (failure != null) return;
        if ((mask & INVALIDATION) != 0) {
            invalidate("Capture close observations were lost; restart Minecraft before a new capture");
            return;
        }
        var match = NAME.matcher(name);
        if (!match.matches() || retiredThrough != null && !CaptureFrame.newer(match.group(1), retiredThrough)) return;
        State previous = names.get(name);
        if ((mask & (DELETE | MOVED_FROM | MOVED_TO | ISDIR)) != 0 || previous == State.READY || previous == State.INVALID)
            remember(name, State.INVALID);
        else if ((mask & CLOSE_WRITE) != 0)
            remember(name, previous == State.PENDING ? State.READY : State.INVALID);
        else if ((mask & CREATE) != 0)
            remember(name, previous == null ? State.PENDING : State.INVALID);
        else if ((mask & MODIFY) != 0)
            // An old path reopened after watcher startup has no observed creation in this epoch.
            remember(name, previous == State.PENDING ? State.PENDING : State.INVALID);
    }

    private void remember(String name, State state) {
        if (!names.containsKey(name) && names.size() == 128) {
            invalidate("Capture close observation cache exhausted; restart Minecraft before a new capture");
            return;
        }
        names.put(name, state);
    }

    private void invalidate(String reason) { if (failure == null) failure = reason; }
    private void check() throws IOException {
        if (failure != null) throw new IOException(failure);
    }

    @Override public synchronized void close() {
        invalidate("Capture close observation epoch is closed");
        try {
            if (fd >= 0) {
                int owned = fd;
                fd = -1;
                close.invokeInt(new Object[] {owned});
            }
        } finally {
            if (buffer != null) { buffer.close(); buffer = null; }
            names.clear();
        }
    }
}

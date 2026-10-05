package org.stellarbridge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.system.MemoryUtil;

/** File-backed SPSC triple buffer, adapted from SkyCraft's back/middle/front ownership algorithm. */
final class RawFrameBus implements AutoCloseable {
    static final int HEADER = 4096, MAX_WIDTH = 3840, MAX_HEIGHT = 2160;
    static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long MAX = 9007199254740991L;
    private static final byte[] MAGIC = "SBMCFRM1".getBytes(StandardCharsets.US_ASCII);
    interface LibC extends Library {
        int open(String path, int flags, int mode);
        int ftruncate(int fd, NativeLong length);
        Pointer mmap(Pointer address, NativeLong length, int protection, int flags, int fd, NativeLong offset);
        int munmap(Pointer address, NativeLong length);
        int close(int fd);
    }
    private static final LibC C = Native.load("c", LibC.class);
    final Path path;
    final String run, session;
    final int width, height, planeBytes, stride, bytes;
    private final long createdNanos = System.nanoTime();
    private final byte[] identity;
    private Pointer address;
    private ByteBuffer map;
    private int fd = -1, front = 2, producerPid;
    private long previousCounter;
    private long heartbeatMillis, heartbeatNanos;
    private boolean stopped, pendingDelete;

    RawFrameBus(Path directory, String run, String session, int width, int height, long nowMs) throws IOException {
        if (!Platform.isLinux() || Native.POINTER_SIZE != 8) throw new IOException("Raw frames require 64-bit Linux");
        require(run.matches("[0-9a-f]{32}") && session.matches("[0-9a-f]{32}"), "Invalid raw capture identity");
        require(width >= 1 && width <= MAX_WIDTH && height >= 1 && height <= MAX_HEIGHT, "Raw capture dimensions exceed 4K bounds");
        this.run = run; this.session = session; this.width = width; this.height = height;
        planeBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        stride = Math.toIntExact((HEADER + 2L * planeBytes + 63) & ~63L);
        bytes = Math.addExact(HEADER, Math.multiplyExact(stride, 3));
        path = new BridgeFiles(directory).checked("eve-frames-" + run + ".bin");
        identity = (run + session).getBytes(StandardCharsets.US_ASCII);
        try {
            fd = C.open(path.toString(), 2 | 64 | 128 | 0x20000 | 0x80000, 0600); // RDWR, CREAT, EXCL, NOFOLLOW, CLOEXEC.
            if (fd < 0) throw new IOException("Cannot exclusively create raw capture mapping: errno " + Native.getLastError());
            if (C.ftruncate(fd, new NativeLong(bytes)) != 0) throw new IOException("Cannot size raw capture mapping");
            address = C.mmap(null, new NativeLong(bytes), 3, 1, fd, new NativeLong(0)); // READ|WRITE, MAP_SHARED.
            if (Pointer.nativeValue(address) == -1) { address = null; throw new IOException("Cannot map raw capture file"); }
            map = address.getByteBuffer(0, bytes).order(ByteOrder.LITTLE_ENDIAN);
            map.put(0, MAGIC).putInt(8, 1).putInt(12, HEADER).putInt(16, width).putInt(20, height)
                    .putInt(24, stride).putInt(28, bytes).put(32, identity).putInt(96, Math.toIntExact(ProcessHandle.current().pid()));
            LONG.setRelease(map, 264, nowMs);
            INT.setRelease(map, 256, 1);
        } catch (IOException | RuntimeException | Error failure) {
            boolean created = fd >= 0;
            unmap();
            if (created) Files.deleteIfExists(path);
            throw failure;
        }
    }

    /** Returns an independent CPU copy; no mapped view escapes this class. Called under CaptureStream's lock. */
    Frame poll(long nowMs, long nowNanos) throws IOException {
        try { return read(nowMs, nowNanos); }
        catch (IOException | RuntimeException failure) { stop(); throw failure; }
    }
    private Frame read(long nowMs, long nowNanos) throws IOException {
        require(map != null && !stopped, "Raw capture mapping is stopped");
        LONG.setRelease(map, 264, nowMs);
        validateHeader();
        int pid = (int) INT.getAcquire(map, 192), state = (int) INT.getAcquire(map, 196);
        require(state >= 0 && state <= 3, "Invalid raw producer state");
        if (state == 0) {
            require(producerPid == 0, "Raw producer reset its claimed run");
            if (nowNanos - createdNanos > 3_000_000_000L) throw new IOException("Native raw capture unavailable: no capability acknowledgement");
            return null;
        }
        require(pid != 0 && (producerPid == 0 || producerPid == pid), "Raw capture producer changed; start a new run");
        producerPid = pid;
        int error = (int) INT.getAcquire(map, 200);
        if (state == 3 || state == 2 && error != 0) throw new IOException("Native raw capture failed (error " + error + ")");
        if (state == 2) throw new IOException("Native raw capture stopped");
        long heartbeat = (long) LONG.getAcquire(map, 208);
        require(heartbeat > 0 && heartbeat <= MAX && nowMs - heartbeat <= 3000 && heartbeat - nowMs <= 1000,
                "Native raw capture heartbeat expired");
        require(heartbeatMillis == 0 || nowNanos - heartbeatNanos <= 3_000_000_000L,
                "Native raw capture monotonic lease expired");
        require(heartbeat >= heartbeatMillis, "Native raw capture heartbeat moved backwards");
        if (heartbeat > heartbeatMillis) { heartbeatMillis = heartbeat; heartbeatNanos = nowNanos; }
        int middle = (int) INT.getAcquire(map, 128);
        validMiddle(middle);
        if ((middle & 4) == 0) return null;
        int old = (int) INT.getAndSet(map, 128, front);
        validMiddle(old);
        require((old & 4) != 0, "Raw frame ownership changed unexpectedly");
        front = old & 3;
        int at = HEADER + front * stride;
        long counter = map.getLong(at), publication = map.getLong(at + 8);
        int length = map.getInt(at + 16);
        require(counter > previousCounter && counter <= MAX && publication > 0 && publication <= MAX,
                "Invalid raw frame sequence or time");
        previousCounter = counter;
        require(length > 0 && length <= 2048 && map.getInt(at + 20) == 1, "Invalid raw frame slot layout");
        zeros(at + 24, at + 64);
        zeros(at + 64 + length, at + HEADER);
        byte[] descriptor = new byte[length];
        map.get(at + 64, descriptor);
        var metadata = CaptureFrame.parse(descriptor);
        require(metadata.version() == 5 && metadata.width() == width && metadata.height() == height
                && metadata.run().equals(run) && metadata.link().session().equals(session)
                && metadata.id().equals(metadata.time() + "-" + counter), "Raw frame identity or dimensions mismatch");
        require(publication - nowMs <= 1000 && nowMs - publication <= 3000, "Raw frame publication expired");
        Frame frame = new Frame(metadata, planeBytes);
        try {
            int pixels = at + HEADER;
            MemoryUtil.memCopy(MemoryUtil.memAddress(map) + pixels, MemoryUtil.memAddress(frame.color), planeBytes);
            MemoryUtil.memCopy(MemoryUtil.memAddress(map) + pixels + planeBytes, MemoryUtil.memAddress(frame.depth), planeBytes);
            for (int offset = 0; offset < planeBytes; offset += 4) {
                float depth = frame.depth.getFloat(offset);
                require(Float.isFinite(depth) && depth >= 0 && (depth > 0 || frame.color.get(offset + 3) == 0),
                        "Invalid raw coverage/depth pair");
            }
            validateHeader();
            require((int) INT.getAcquire(map, 192) == producerPid && (int) INT.getAcquire(map, 196) == 1,
                    "Raw producer stopped during frame copy");
            LONG.setRelease(map, 272, counter);
            return frame;
        } catch (IOException | RuntimeException | Error failure) { frame.close(); throw failure; }
    }

    private void validateHeader() throws IOException {
        for (int i = 0; i < MAGIC.length; i++) require(map.get(i) == MAGIC[i], "Raw mapping magic changed");
        for (int i = 0; i < identity.length; i++) require(map.get(32 + i) == identity[i], "Raw mapping identity changed");
        require(map.getInt(8) == 1 && map.getInt(12) == HEADER && map.getInt(16) == width && map.getInt(20) == height
                && map.getInt(24) == stride && map.getInt(28) == bytes, "Raw mapping layout changed");
        zeros(100, 128); zeros(132, 192); zeros(204, 208); zeros(224, 256); zeros(260, 264); zeros(280, HEADER);
    }
    private void zeros(int from, int to) throws IOException {
        for (int i = from; i < to; i++) require(map.get(i) == 0, "Raw mapping reserved bytes changed");
    }
    private static void validMiddle(int value) throws IOException {
        require((value & ~7) == 0 && (value & 3) < 3, "Invalid raw frame ownership word");
    }
    void stop() {
        if (map != null && !stopped) { INT.setRelease(map, 256, 2); stopped = true; }
    }
    /** Retain at most one stopped run until native confirms it no longer writes. */
    boolean reap() throws IOException {
        stop();
        if (map != null) {
            // Win the same atomic claim used by the producer; a plain pid==0 read races its startup.
            boolean cancelled = (boolean) INT.compareAndSet(map, 192, 0, -1);
            if (!cancelled && (int) INT.getAcquire(map, 196) != 2) return false;
            pendingDelete = true;
            unmap();
        }
        if (pendingDelete) {
            Files.deleteIfExists(path);
            pendingDelete = false;
        }
        return true;
    }
    @Override public void close() {
        stop();
        unmap(); // An unresolved producer keeps its file; never truncate or reuse it.
    }
    private void unmap() {
        map = null;
        if (address != null) { C.munmap(address, new NativeLong(bytes)); address = null; }
        if (fd >= 0) { C.close(fd); fd = -1; }
    }
    static int[] dimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid framebuffer dimensions");
        double scale = Math.min(1, Math.min((double) MAX_WIDTH / width, (double) MAX_HEIGHT / height));
        return new int[] {Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale))};
    }
    static final class Frame implements AutoCloseable {
        final CaptureFrame.Metadata metadata;
        final ByteBuffer color, depth;
        private boolean closed;
        Frame(CaptureFrame.Metadata metadata, int planeBytes) {
            this.metadata = metadata;
            color = MemoryUtil.memAlloc(planeBytes).order(ByteOrder.LITTLE_ENDIAN);
            try { depth = MemoryUtil.memAlloc(planeBytes).order(ByteOrder.LITTLE_ENDIAN); }
            catch (RuntimeException | Error failure) { MemoryUtil.memFree(color); throw failure; }
        }
        @Override public void close() {
            if (!closed) { closed = true; MemoryUtil.memFree(color); MemoryUtil.memFree(depth); }
        }
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}

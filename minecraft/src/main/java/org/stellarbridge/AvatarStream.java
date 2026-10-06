package org.stellarbridge;

import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft side of the EVE pose stream: the request, Lua's component tables and the native pose mapping
 * (eve-pose-RUN.bin, the raw mapping's ownership protocol with 64 KiB pose slots). Render thread only.
 */
final class AvatarStream implements AutoCloseable {
    static final int HEADER = 4096, SLOT = 65536, SLOT_HEADER = 128, CAPACITY = (SLOT - SLOT_HEADER) / 4, TOTAL = HEADER + 3 * SLOT;
    static final int WORLD = 1, BONES = 2, MORPHS = 4;
    private static final long MAX = 9007199254740991L;
    private static final byte[] MAGIC = "SBMCPOS1".getBytes(StandardCharsets.US_ASCII);

    /**
     * A Lua component table; anchor is Unreal X, Y, Z and units per block, or null without an entered link.
     * controller says whether poses carry the player controller's rotation (and the adapter may turn its camera).
     */
    record Table(String run, long generation, String session, double[] anchor, boolean controller,
                 List<AvatarRecording.Component> components, List<Parameter> parameters, int floats) {
        /** Payload floats of the components; the parameters' floats follow them. */
        int componentFloats() {
            int total = floats;
            for (Parameter parameter : parameters) total -= parameter.floats();
            return total;
        }
    }
    /** A material parameter the game animates (scalar: 1 float, vector: 4) on a component's material slot. */
    record Parameter(int component, String slot, String name, int floats) { }
    /**
     * One published pose: per component status bits and its floats (world, component-space bones, morph weights),
     * the controller's Unreal Pitch, Yaw, Roll in degrees or null (read before this frame's turn), and whether the
     * adapter turned Stellar Blade's camera toward Minecraft's this frame.
     */
    record Sample(long counter, long publication, long generation, int[] status, float[] payload, float[] rotation,
                  boolean lookApplied) { }

    final String run;
    final Path path;
    private final BridgeFiles files;
    private final long createdNanos = System.nanoTime();
    private Pointer address;
    private ByteBuffer map;
    private int fd = -1, front = 2, producerPid;
    private long previousCounter, heartbeatMillis, heartbeatNanos, requestMillis;
    private boolean stopped, pendingDelete;

    AvatarStream(Path directory, String run, long nowMs) throws IOException {
        if (!Platform.isLinux() || Native.POINTER_SIZE != 8) throw new IOException("The pose stream requires 64-bit Linux");
        require(run.matches("[0-9a-f]{32}"), "Invalid pose stream identity");
        this.run = run;
        files = new BridgeFiles(directory);
        path = files.checked("eve-pose-" + run + ".bin");
        try {
            fd = RawFrameBus.C.open(path.toString(), 2 | 64 | 128 | 0x20000 | 0x80000, 0600); // RDWR, CREAT, EXCL, NOFOLLOW, CLOEXEC.
            if (fd < 0) throw new IOException("Cannot exclusively create the pose mapping: errno " + Native.getLastError());
            if (RawFrameBus.C.ftruncate(fd, new NativeLong(TOTAL)) != 0) throw new IOException("Cannot size the pose mapping");
            address = RawFrameBus.C.mmap(null, new NativeLong(TOTAL), 3, 1, fd, new NativeLong(0));
            if (Pointer.nativeValue(address) == -1) { address = null; throw new IOException("Cannot map the pose file"); }
            map = address.getByteBuffer(0, TOTAL).order(ByteOrder.LITTLE_ENDIAN);
            map.put(0, MAGIC).putInt(8, 1).putInt(12, HEADER).putInt(16, SLOT).putInt(20, CAPACITY).putInt(28, TOTAL)
                    .put(32, run.getBytes(StandardCharsets.US_ASCII)).putInt(96, Math.toIntExact(ProcessHandle.current().pid()));
            RawFrameBus.LONG.setRelease(map, 264, nowMs);
            RawFrameBus.INT.setRelease(map, 256, 1);
            request(true, nowMs);
        } catch (IOException | RuntimeException | Error failure) {
            boolean created = fd >= 0;
            unmap();
            if (created) Files.deleteIfExists(path);
            throw failure;
        }
    }

    private void request(boolean active, long nowMs) throws IOException {
        files.write("eve-pose-request.txt", "SBMC_POSE_REQUEST 1\nRUN " + run + "\nTIME " + nowMs + "\nSTATE "
                + (active ? "active" : "stopped") + "\nEND\n");
        requestMillis = nowMs;
    }

    /** Keeps the request and the consumer lease fresh; call at least every second. */
    void heartbeat(long nowMs) throws IOException {
        require(map != null && !stopped, "Pose stream is stopped");
        RawFrameBus.LONG.setRelease(map, 264, nowMs);
        if (nowMs - requestMillis >= 1000 || nowMs < requestMillis) request(true, nowMs);
    }

    /** The newest valid table of this run from Lua's two alternating slots, or null. */
    Table table() throws IOException {
        return select(files.read("eve-pose-components.0"), files.read("eve-pose-components.1"), run);
    }

    /** The newest published pose, or null when none is new. Throws when the producer failed or stopped. */
    Sample poll(Table table, long nowMs, long nowNanos) throws IOException {
        try { return read(table, nowMs, nowNanos); }
        catch (IOException | RuntimeException failure) { stop(); throw failure; }
    }
    private Sample read(Table table, long nowMs, long nowNanos) throws IOException {
        require(map != null && !stopped, "Pose stream is stopped");
        validateHeader();
        int pid = (int) RawFrameBus.INT.getAcquire(map, 192), state = (int) RawFrameBus.INT.getAcquire(map, 196);
        require(state >= 0 && state <= 3, "Invalid pose producer state");
        if (state == 0) {
            require(producerPid == 0, "Pose producer reset its claimed run");
            if (nowNanos - createdNanos > 5_000_000_000L)
                throw new IOException("No native pose producer: the adapter, Lua revision 32 and a loaded EVE are required");
            return null;
        }
        require(pid != 0 && (producerPid == 0 || producerPid == pid), "Pose producer changed; start a new stream");
        producerPid = pid;
        int error = (int) RawFrameBus.INT.getAcquire(map, 200);
        if (state == 3 || state == 2 && error != 0) throw new IOException("Native pose stream failed (error " + error + ")");
        if (state == 2) throw new IOException("Native pose stream stopped");
        long heartbeat = (long) RawFrameBus.LONG.getAcquire(map, 208);
        require(heartbeat > 0 && heartbeat <= MAX && nowMs - heartbeat <= 3000 && heartbeat - nowMs <= 1000, "Native pose heartbeat expired");
        require(heartbeatMillis == 0 || nowNanos - heartbeatNanos <= 3_000_000_000L, "Native pose monotonic lease expired");
        require(heartbeat >= heartbeatMillis, "Native pose heartbeat moved backwards");
        if (heartbeat > heartbeatMillis) { heartbeatMillis = heartbeat; heartbeatNanos = nowNanos; }
        int middle = (int) RawFrameBus.INT.getAcquire(map, 128);
        validMiddle(middle);
        if ((middle & 4) == 0) return null;
        int old = (int) RawFrameBus.INT.getAndSet(map, 128, front);
        validMiddle(old);
        require((old & 4) != 0, "Pose slot ownership changed unexpectedly");
        front = old & 3;
        int at = HEADER + front * SLOT;
        long counter = map.getLong(at), publication = map.getLong(at + 8), generation = map.getLong(at + 16);
        int count = map.getInt(at + 28), floats = map.getInt(at + 32);
        require(counter > previousCounter && counter <= MAX && publication > 0 && publication <= MAX, "Invalid pose sequence or time");
        previousCounter = counter;
        require(map.getInt(at + 24) == 1 && generation > 0 && count >= 1 && count <= 16 && floats >= 0 && floats <= CAPACITY,
                "Invalid pose slot layout");
        int rotated = map.getInt(at + 36);
        require(rotated == 0 || rotated == 1 || rotated == 3, "Invalid controller rotation status");
        if (rotated == 0) zeros(at + 40, at + 52);
        zeros(at + 52, at + 64);
        zeros(at + 64 + 4 * count, at + SLOT_HEADER);
        RawFrameBus.LONG.setRelease(map, 272, counter);
        if (table == null || generation != table.generation) return new Sample(counter, publication, generation, null, null, null, false);
        require(count == table.components.size() && floats == table.floats, "Pose slot does not match its component table");
        int[] status = new int[count];
        for (int i = 0; i < count; i++) {
            status[i] = map.getInt(at + 64 + 4 * i);
            require((status[i] & ~7) == 0, "Invalid pose component status");
        }
        float[] payload = new float[floats];
        map.slice(at + SLOT_HEADER, 4 * floats).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(payload);
        validateHeader();
        require((int) RawFrameBus.INT.getAcquire(map, 192) == producerPid && (int) RawFrameBus.INT.getAcquire(map, 196) == 1,
                "Pose producer stopped during the copy");
        for (float value : payload) require(Float.isFinite(value), "Non-finite pose value");
        float[] rotation = null;
        if (rotated != 0) {
            rotation = new float[] {map.getFloat(at + 40), map.getFloat(at + 44), map.getFloat(at + 48)};
            for (float value : rotation) require(Float.isFinite(value), "Non-finite controller rotation");
        }
        return new Sample(counter, publication, generation, status, payload, rotation, rotated == 3);
    }

    /**
     * Minecraft's camera (Unreal degrees), which the adapter turns Stellar Blade's camera toward through look input while
     * this run owns forwarded input. Written only after the producer claimed the mapping: time at 288, Pitch and Yaw as
     * one 64-bit word at 296, state at 304.
     */
    void look(float pitch, float yaw, long nowMs) {
        if (map == null || stopped || producerPid == 0 || (int) RawFrameBus.INT.getAcquire(map, 196) != 1) return;
        long packed = Integer.toUnsignedLong(Float.floatToRawIntBits(pitch)) | (long) Float.floatToRawIntBits(yaw) << 32;
        RawFrameBus.LONG.setRelease(map, 296, packed);
        RawFrameBus.LONG.setRelease(map, 288, nowMs);
        RawFrameBus.INT.setRelease(map, 304, 1);
    }
    void lookOff() {
        if (map != null && !stopped) RawFrameBus.INT.setRelease(map, 304, 0);
    }

    /** Producer statistics: published poses, then last, maximum and mean native read microseconds. */
    long[] statistics() {
        if (map == null) return new long[4];
        long published = (long) RawFrameBus.LONG.getAcquire(map, 216), total = (long) RawFrameBus.LONG.getAcquire(map, 232);
        return new long[] {published, Integer.toUnsignedLong(map.getInt(224)), Integer.toUnsignedLong(map.getInt(228)),
                published > 0 ? total / published : 0};
    }

    /** Decodes a sample of the table's generation into a component-space pose; false if a drawn part lacks data. */
    static boolean decode(Table table, Sample sample, AvatarModel model, AvatarRecording.Pose target) {
        int offset = 0;
        boolean complete = true;
        for (int i = 0; i < table.components.size(); i++) {
            var c = table.components.get(i);
            int bones = c.recorded() ? c.bones().length : 0, status = sample.status[i];
            boolean drawn = model.draws(i);
            if (drawn && ((status & WORLD) == 0 || bones > 0 && (status & BONES) == 0)) complete = false;
            System.arraycopy(sample.payload, offset, target.world()[i], 0, 10);
            if (bones > 0) System.arraycopy(sample.payload, offset + 10, target.local()[i], 0, 10 * bones);
            if (c.weights() > 0) {
                if ((status & MORPHS) != 0) System.arraycopy(sample.payload, offset + 10 + 10 * bones, target.weights()[i], 0, c.weights());
                else java.util.Arrays.fill(target.weights()[i], 0);
            }
            offset += 10 + 10 * bones + c.weights();
        }
        return complete;
    }

    /** The sample's material parameters by "slot/name", from the floats after the components. */
    static java.util.Map<String, float[]> parameters(Table table, Sample sample) {
        java.util.Map<String, float[]> values = new java.util.HashMap<>();
        int offset = table.componentFloats();
        for (Parameter parameter : table.parameters) {
            values.put(parameter.slot() + "/" + parameter.name(), java.util.Arrays.copyOfRange(sample.payload, offset, offset + parameter.floats()));
            offset += parameter.floats();
        }
        return values;
    }

    static AvatarRecording.Pose emptyPose(Table table) {
        int n = table.components.size();
        float[][] world = new float[n][10], local = new float[n][], weights = new float[n][];
        for (int i = 0; i < n; i++) {
            var c = table.components.get(i);
            if (c.recorded()) local[i] = new float[10 * c.bones().length];
            weights[i] = new float[c.weights()];
        }
        return new AvatarRecording.Pose(world, local, weights);
    }

    static Table select(String slot0, String slot1, String run) {
        Table a = parse(slot0), b = parse(slot1);
        if (a != null && !a.run.equals(run)) a = null;
        if (b != null && !b.run.equals(run)) b = null;
        if (a != null && b != null) return a.generation >= b.generation ? a : b;
        return a != null ? a : b;
    }

    /** Mirrors the native grammar; invalid or partially written tables are null. */
    static Table parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 65536 || !text.endsWith("\n")) return null;
        try {
            String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
            int at = 0;
            require(lines[at++].equals("SBMC_POSE_COMPONENTS 1"), "header");
            String run = value(lines[at++], "RUN");
            require(run.matches("[0-9a-f]{32}"), "run");
            long generation = Long.parseLong(value(lines[at++], "GENERATION"));
            require(generation > 0 && generation <= MAX, "generation");
            String session = value(lines[at++], "SESSION");
            require(session.equals("none") || session.matches("[0-9a-f]{32}"), "session");
            String[] anchorFields = lines[at++].split(" ", -1);
            require(anchorFields[0].equals("ANCHOR"), "anchor");
            double[] anchor = null;
            if (anchorFields.length == 5) {
                anchor = new double[4];
                for (int k = 0; k < 4; k++) {
                    anchor[k] = Double.parseDouble(anchorFields[k + 1]);
                    require(Double.isFinite(anchor[k]), "anchor value");
                }
                require(anchor[3] > 0, "scale");
            } else require(anchorFields.length == 2 && anchorFields[1].equals("none"), "anchor form");
            String[] controller = lines[at++].split(" ", -1);
            require(controller[0].equals("CONTROLLER"), "controller");
            boolean controlled = controller.length == 8;
            if (controlled) require(controller[1].matches("[0-9a-f]{16}") && controller[2].matches("[0-9a-f]{16}")
                    && controller[3].matches("[0-9a-f]{8}") && Integer.parseInt(controller[3], 16) % 4 == 0
                    && Integer.parseInt(controller[3], 16) > 0 && Integer.parseInt(controller[3], 16) < 0x4000
                    && controller[4].matches("[0-9a-f]{16}") && controller[5].matches("[0-9a-f]{16}")
                    && scale(controller[6]) && scale(controller[7]), "controller fields");
            else require(controller.length == 2 && controller[1].equals("none"), "controller form");
            int count = Integer.parseInt(value(lines[at++], "COUNT"));
            require(count >= 1 && count <= 16, "count");
            List<AvatarRecording.Component> components = new ArrayList<>();
            long floats = 0;
            for (int i = 1; i <= count; i++) {
                String[] f = lines[at++].split(" ", -1);
                require(f.length == 10 && f[0].equals("COMPONENT") && f[1].equals(Integer.toString(i)), "component");
                for (int k = 2; k <= 4; k++) require(f[k].matches("[0-9a-f]{16}"), "address");
                int bones = Integer.parseInt(f[5]), morphs = Integer.parseInt(f[6]);
                require(bones >= 0 && bones <= 256 && morphs >= 0 && morphs <= 1024 && !f[7].isEmpty() && !f[8].isEmpty() && !f[9].isEmpty(), "counts");
                String[] boneNames = bones > 0 ? names(lines[at++], "BONES", i, bones) : null;
                String[] morphNames = morphs > 0 ? names(lines[at++], "MORPHS", i, morphs) : new String[0];
                String leader = f[9].equals("-") ? null : f[9];
                require(bones > 0 ? leader == null : true, "a follower carries no bones");
                // A component without a leader and without bones is drawn in its reference pose.
                components.add(new AvatarRecording.Component(f[7], f[8], leader, leader == null ? (boneNames == null ? new String[0] : boneNames) : null,
                        morphNames, morphs));
                floats += 10 + 10L * bones + morphs;
            }
            int parameterCount = Integer.parseInt(value(lines[at++], "PARAMETERS"));
            require(parameterCount >= 0 && parameterCount <= 32, "parameter count");
            List<Parameter> parameters = new ArrayList<>();
            for (int i = 1; i <= parameterCount; i++) {
                String[] f = lines[at++].split(" ", -1);
                require(f.length == 11 && f[0].equals("PARAMETER") && f[1].equals(Integer.toString(i)), "parameter");
                int component = Integer.parseInt(f[2]), width = Integer.parseInt(f[10]);
                require(component >= 1 && component <= count && !f[3].isEmpty() && !f[4].isEmpty() && f[5].matches("[0-9a-f]{16}")
                        && f[6].matches("[0-9a-f]{16}") && f[7].matches("[0-9a-f]{8}") && Integer.parseInt(f[7], 16) % 8 == 0
                        && Integer.parseInt(f[7], 16) > 0 && Integer.parseInt(f[7], 16) < 0x4000 && Integer.parseInt(f[8]) >= 0
                        && Integer.parseInt(f[8]) < 1024 && Long.parseLong(f[9]) > 0 && Long.parseLong(f[9]) <= Integer.MAX_VALUE
                        && (width == 1 || width == 4), "parameter fields");
                parameters.add(new Parameter(component, f[3], f[4], width));
                floats += width;
            }
            require(floats <= CAPACITY && lines[at++].equals("END " + generation) && at == lines.length, "end");
            return new Table(run, generation, session, anchor, controlled, List.copyOf(components), List.copyOf(parameters), (int) floats);
        } catch (IOException | RuntimeException rejected) {
            return null;
        }
    }
    /** A look input scale the adapter accepts: finite, magnitude 0.01 to 100. */
    private static boolean scale(String text) {
        try {
            float value = Math.abs(Float.parseFloat(text));
            return text.matches("[-+0-9.eE]+") && value >= 0.01f && value <= 100;
        } catch (NumberFormatException failure) {
            return false;
        }
    }
    private static String value(String line, String key) throws IOException {
        require(line.startsWith(key + " ") && line.indexOf(' ', key.length() + 1) < 0, key);
        return line.substring(key.length() + 1);
    }
    private static String[] names(String line, String key, int index, int count) throws IOException {
        String[] f = line.split(" ", -1);
        require(f.length == count + 2 && f[0].equals(key) && f[1].equals(Integer.toString(index)), key);
        String[] names = java.util.Arrays.copyOfRange(f, 2, f.length);
        for (String name : names) require(!name.isEmpty(), key);
        return names;
    }

    private void validateHeader() throws IOException {
        for (int i = 0; i < MAGIC.length; i++) require(map.get(i) == MAGIC[i], "Pose mapping magic changed");
        byte[] identity = run.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < identity.length; i++) require(map.get(32 + i) == identity[i], "Pose mapping identity changed");
        require(map.getInt(8) == 1 && map.getInt(12) == HEADER && map.getInt(16) == SLOT && map.getInt(20) == CAPACITY
                && map.getInt(28) == TOTAL, "Pose mapping layout changed");
        zeros(24, 28); zeros(64, 96); zeros(100, 128); zeros(132, 192); zeros(204, 208); zeros(240, 256); zeros(260, 264);
        zeros(280, 288); zeros(308, HEADER); // 288..308: Minecraft's camera for the controller.
    }
    private void zeros(int from, int to) throws IOException {
        for (int i = from; i < to; i++) require(map.get(i) == 0, "Pose mapping reserved bytes changed");
    }
    private static void validMiddle(int value) throws IOException {
        require((value & ~7) == 0 && (value & 3) < 3, "Invalid pose ownership word");
    }

    void stop() {
        if (map != null && !stopped) {
            RawFrameBus.INT.setRelease(map, 256, 2);
            stopped = true;
            try { request(false, System.currentTimeMillis()); }
            catch (IOException failure) { org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("Pose stop not published; its lease will expire", failure); }
        }
    }
    /** Deletes the mapping once native released or never claimed it; false keeps an unresolved run for a later retry. */
    boolean reap() throws IOException {
        stop();
        if (map != null) {
            boolean cancelled = (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, -1);
            if (!cancelled && (int) RawFrameBus.INT.getAcquire(map, 196) != 2) return false;
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
        unmap(); // An unresolved producer keeps its file; it is never reset for another run.
    }
    private void unmap() {
        map = null;
        if (address != null) { RawFrameBus.C.munmap(address, new NativeLong(TOTAL)); address = null; }
        if (fd >= 0) { RawFrameBus.C.close(fd); fd = -1; }
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}

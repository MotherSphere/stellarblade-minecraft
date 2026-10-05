package org.stellarbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** A bounded one-shot native capture; no packet is executable. */
final class CaptureFrame {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_INTEGER = 9007199254740991L;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static volatile CaptureCloseWatch closeWatch;
    record Position(double x, double y, double z) { }
    record Camera(Position position, double pitch, double yaw, double roll) { }
    record Pawn(Position position, double halfHeight) { }
    record Context(long pawn, long world) { }
    record Link(String session, int ack, double scale, Position feet) { }
    record Metadata(String id, long time, double fov, int colorBytes, int maskBytes, int depthBytes,
                    Camera camera, Pawn pawn, Context context, Link link, int width, int height, int version, String run) {
        Metadata(String id, long time, double fov, int colorBytes, int maskBytes, int depthBytes,
                 Camera camera, Pawn pawn, Context context, Link link, int width, int height, int version) {
            this(id, time, fov, colorBytes, maskBytes, depthBytes, camera, pawn, context, link, width, height, version, null);
        }
    }
    record Observation(CaptureCloseWatch watch, Path directory, String id) {
        void verify(byte[] descriptor) throws IOException {
            verifyUnchanged(directory, descriptor);
            watch.requireReady(directory, id);
        }
    }
    record Loaded(Metadata metadata, byte[] descriptor, byte[] color, byte[] mask, float[] depth, Observation observation) {
        Loaded(Metadata metadata, byte[] descriptor, byte[] color, byte[] mask, float[] depth) {
            this(metadata, descriptor, color, mask, depth, null);
        }
        void verifyObservation() throws IOException {
            if (metadata.version >= 3) {
                require(observation != null, "Asynchronous capture has no close observation");
                observation.verify(descriptor);
            }
        }
        void retire() throws IOException {
            require(metadata.version == 4 && observation != null, "Only continuous observed captures can retire");
            observation.verify(descriptor);
            observation.watch.retire(observation.directory, metadata.id);
        }
    }

    static synchronized void startWatching(Path directory) throws IOException {
        stopWatching();
        closeWatch = new CaptureCloseWatch(directory);
    }

    static synchronized void stopWatching() {
        var previous = closeWatch;
        closeWatch = null;
        if (previous != null) previous.close();
    }

    static void requireWatching(Path directory) throws IOException {
        var watch = closeWatch;
        if (watch == null) throw new IOException("Asynchronous capture watcher unavailable; restart Minecraft before capturing");
        watch.available(directory);
    }

    static Metadata parse(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= 2048, "Capture descriptor exceeds limits");
        for (byte value : bytes)
            require(value == '\n' || value >= 32 && value <= 126, "Capture descriptor must be ASCII with LF endings");
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        require((lines.length == 11 || lines.length == 12) && lines[lines.length - 1].isEmpty(),
                "Capture descriptor has an invalid line count");
        String[] header = fields(lines[0], "SBMC_FRAME", 4);
        require(header[1].matches("[1234]") && header[2].matches("[0-9]+-[0-9]+"), "Unsupported capture header");
        int version = Integer.parseInt(header[1]);
        require(lines.length == (version == 4 ? 12 : 11), "Capture descriptor version has an invalid line count");
        String id = header[2];
        require(version < 3 || id.matches("[0-9]{1,16}-[0-9]{1,16}"), "Asynchronous capture generation exceeds limits");
        long time = integer(header[3], 0, MAX_INTEGER);
        String[] sequence = id.split("-", -1);
        require(integer(sequence[0], 0, MAX_INTEGER) == time, "Capture ID timestamp mismatch");
        integer(sequence[1], 1, MAX_INTEGER);
        String[] size = fields(lines[1], "SIZE", 3);
        int width = (int) integer(size[1], 1, 1280), height = (int) integer(size[2], 1, 720);
        require(supportedSize(width, height), "Only 320 by 180 and 1280 by 720 captures are supported");
        require(version < 3 || width == 1280 && height == 720, "Asynchronous capture requires HD dimensions");
        String[] camera = fields(lines[2], "CAMERA", 8);
        for (int i = 1; i < camera.length; i++) decimal(camera[i]);
        double fov = decimal(camera[7]);
        require(fov > 0 && fov < 180, "Invalid capture FOV");
        String[] pawn = fields(lines[3], "PAWN", 5);
        for (int i = 1; i < pawn.length; i++) decimal(pawn[i]);
        require(decimal(pawn[4]) > 0, "Invalid capsule height");
        String[] context = fields(lines[4], "CONTEXT", 3);
        integer(context[1], 1, MAX_INTEGER); integer(context[2], 1, MAX_INTEGER);
        String[] link = fields(lines[5], "LINK", 7);
        boolean unlinked = link[1].equals("none");
        require(unlinked || link[1].matches("[0-9a-f]{32}"), "Invalid bridge session");
        require(version != 4 || !unlinked, "Continuous capture requires a terrain session");
        long ack = integer(link[2], unlinked ? 0 : 1, Integer.MAX_VALUE);
        require(decimal(link[3]) > 0, "Invalid capture scale");
        double x = decimal(link[4]), y = decimal(link[5]), z = decimal(link[6]);
        require(!unlinked || ack == 0 && x == 0 && y == 0 && z == 0, "Unlinked capture has bridge coordinates");
        int color = imageLine(lines[6], "COLOR", id, version >= 2 ? "color.bmp" : "color.png", version >= 3);
        int mask = imageLine(lines[7], "MASK", id, "mask.png", version >= 3);
        int depth = imageLine(lines[8], "DEPTH", id, "depth.hdr", false);
        String run = version == 4 ? fields(lines[9], "RUN", 2)[1] : null;
        require(run == null || run.matches("[0-9a-f]{32}"), "Invalid capture run");
        require(fields(lines[version == 4 ? 10 : 9], "END", 2)[1].equals(id), "Capture footer mismatch");
        return new Metadata(id, time, fov, color, mask, depth,
                new Camera(new Position(decimal(camera[1]), decimal(camera[2]), decimal(camera[3])),
                        decimal(camera[4]), decimal(camera[5]), decimal(camera[6])),
                new Pawn(new Position(decimal(pawn[1]), decimal(pawn[2]), decimal(pawn[3])), decimal(pawn[4])),
                new Context(integer(context[1], 1, MAX_INTEGER), integer(context[2], 1, MAX_INTEGER)),
                new Link(link[1], (int) ack, decimal(link[3]), new Position(x, y, z)), width, height, version, run);
    }

    static boolean supportedSize(int width, int height) {
        return width == 320 && height == 180 || width == 1280 && height == 720;
    }

    static boolean fresh(long time, long now) {
        return time >= 0 && time <= MAX_INTEGER && now >= 0 && now <= MAX_INTEGER && now - time >= -2 && now - time <= 30;
    }

    static int previewAlpha(int inverseAlpha) {
        int alpha = 255 - inverseAlpha;
        return alpha >= 128 ? alpha : 0;
    }

    static Loaded read(Path directory, long now) throws IOException {
        return readIfNew(directory, now, null);
    }

    static boolean newer(String id, String previous) {
        if (previous == null) return true;
        String[] next = id.split("-"), before = previous.split("-");
        long time = Long.parseLong(next[0]), previousTime = Long.parseLong(before[0]);
        return time > previousTime || time == previousTime && Long.parseLong(next[1]) > Long.parseLong(before[1]);
    }

    static Loaded readIfNew(Path directory, long now, String previousId) throws IOException {
        return readIfNew(directory, now, previousId, closeWatch);
    }

    static Loaded readIfNew(Path directory, long now, String previousId, CaptureCloseWatch watch) throws IOException {
        return readIfNew(directory, now, previousId, watch, false, false);
    }

    static Loaded readForStream(Path directory, long now, String previousId, boolean continuousOnly) throws IOException {
        return readIfNew(directory, now, previousId, closeWatch, true, continuousOnly);
    }

    private static Loaded readIfNew(Path directory, long now, String previousId, CaptureCloseWatch watch,
                                    boolean settleContinuous, boolean continuousOnly) throws IOException {
        byte[] descriptor = readFile(directory, "eve-frame.txt", 2048);
        Metadata metadata = parse(descriptor);
        if (continuousOnly && metadata.version != 4) return null;
        require(settleContinuous && metadata.version == 4 || fresh(metadata.time, now),
                "Capture is older than 30 seconds or has a future timestamp");
        if (!newer(metadata.id, previousId)) return null;
        Observation observation = null;
        if (metadata.version >= 3) {
            require(watch != null, "Asynchronous capture watcher unavailable; restart Minecraft before capturing");
            watch.requireReady(directory, metadata.id);
            observation = new Observation(watch, directory, metadata.id);
        }
        String prefix = "eve-frame-" + metadata.id + "-";
        byte[] color = readFile(directory, prefix + (metadata.version >= 2 ? "color.bmp" : "color.png"), MAX_IMAGE_BYTES);
        byte[] mask = readFile(directory, prefix + "mask.png", MAX_IMAGE_BYTES);
        byte[] depth = readFile(directory, prefix + "depth.hdr", MAX_IMAGE_BYTES);
        require((metadata.version >= 3 || color.length == metadata.colorBytes && mask.length == metadata.maskBytes)
                        && depth.length == metadata.depthBytes,
                "Capture image size does not match its descriptor");
        if (metadata.version >= 2) validateBmp(color, metadata.width, metadata.height);
        else validatePng(color, metadata.width, metadata.height);
        validatePng(mask, metadata.width, metadata.height);
        float[] decodedDepth = decodeDepth(depth, metadata.width, metadata.height);
        if (observation != null) {
            observation.verify(descriptor);
            metadata = new Metadata(metadata.id, metadata.time, metadata.fov, color.length, mask.length, depth.length,
                    metadata.camera, metadata.pawn, metadata.context, metadata.link, metadata.width, metadata.height, metadata.version, metadata.run);
        } else verifyUnchanged(directory, descriptor);
        return new Loaded(metadata, descriptor, color, mask, decodedDepth, observation);
    }

    static void verifyUnchanged(Path directory, byte[] descriptor) throws IOException {
        require(Arrays.equals(descriptor, readFile(directory, "eve-frame.txt", 2048)), "Capture changed during loading; retry");
    }

    private static byte[] readFile(Path directory, String name, int limit) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        validateDirectory(directory);
        Path file = directory.resolve(name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Capture is unavailable or is not a regular file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(limit + 1);
            require(data.length <= limit, "Capture file exceeds limits");
            return data;
        }
    }

    static void validateDirectory(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Capture directory must not contain symlinks or non-directories");
        }
    }

    static void validatePng(byte[] bytes, int width, int height) {
        require(supportedSize(width, height), "Unsupported capture PNG dimensions");
        require(bytes.length >= 45 && bytes.length <= MAX_IMAGE_BYTES, "Capture PNG byte count exceeds limits");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        require(input.getLong() == 0x89504e470d0a1a0aL && input.getInt() == 13 && input.getInt() == 0x49484452,
                "Invalid capture PNG header");
        require(input.getInt() == width && input.getInt() == height && input.get() == 8 && input.get() == 6,
                "Capture PNG must be RGBA8 and match the descriptor dimensions");
        require(input.get() == 0 && input.get() == 0 && input.get() == 0,
                "Capture PNG requires standard compression, filtering and no interlace");
        boolean pixels = false, ended = false;
        input.position(8);
        while (input.remaining() >= 12) {
            int start = input.position();
            int length = input.getInt(), type = input.getInt();
            require(length >= 0 && length <= input.remaining() - 4, "Truncated capture PNG chunk");
            require(type != 0x49484452 || start == 8, "Duplicate capture PNG header");
            CRC32 crc = new CRC32();
            crc.update(bytes, start + 4, length + 4);
            require((int) crc.getValue() == input.getInt(start + 8 + length), "Invalid capture PNG checksum");
            input.position(input.position() + length + 4);
            if (type == 0x49444154) pixels = true;
            if (type == 0x49454e44) {
                require(length == 0 && input.remaining() == 0, "Invalid PNG ending");
                ended = true;
                break;
            }
        }
        require(pixels && ended, "Incomplete capture PNG");
    }

    static void validateBmp(byte[] bytes, int width, int height) {
        require(supportedSize(width, height), "Unsupported capture BMP dimensions");
        require(bytes.length >= 54 && bytes.length <= MAX_IMAGE_BYTES, "Capture BMP byte count exceeds limits");
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(input.getShort(0) == 0x4d42 && input.getInt(2) == bytes.length && input.getInt(6) == 0
                && input.getInt(10) == 54 && input.getInt(14) == 40, "Invalid native BMP header");
        require(input.getInt(18) == width && input.getInt(22) == height && input.getShort(26) == 1
                && input.getShort(28) == 24 && input.getInt(30) == 0,
                "Capture BMP must be bottom-up RGB24 BI_RGB and match the descriptor dimensions");
        int pixelBytes = ((width * 3 + 3) & ~3) * height;
        require(input.getInt(34) == pixelBytes && bytes.length == 54 + pixelBytes
                && input.getInt(46) == 0 && input.getInt(50) == 0, "Invalid native BMP pixel layout");
    }

    static float[] decodeDepth(byte[] bytes) {
        return decodeDepth(bytes, 320, 180);
    }

    static float[] decodeDepth(byte[] bytes, int width, int height) {
        require(supportedSize(width, height), "Unsupported capture depth dimensions");
        require(bytes.length <= MAX_IMAGE_BYTES, "Depth image exceeds limits");
        byte[] header = ("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + height + " +X " + width + "\n").getBytes(StandardCharsets.US_ASCII);
        require(bytes.length >= header.length && Arrays.equals(header, Arrays.copyOf(bytes, header.length)), "Invalid native depth header");
        int offset = header.length;
        float[] depth = new float[width * height];
        byte[][] scanline = new byte[4][width];
        for (int y = 0; y < height; y++) {
            require(offset + 4 <= bytes.length && bytes[offset] == 2 && bytes[offset + 1] == 2
                    && (bytes[offset + 2] & 255) == (width >>> 8) && (bytes[offset + 3] & 255) == (width & 255), "Invalid depth scanline");
            offset += 4;
            for (int channel = 0; channel < 4; channel++) {
                int written = 0;
                while (written < width) {
                    require(offset < bytes.length, "Truncated depth scanline");
                    int code = bytes[offset++] & 255;
                    require(code != 0, "Invalid depth run");
                    int count = code > 128 ? code - 128 : code;
                    require(written + count <= width && offset + (code > 128 ? 1 : count) <= bytes.length, "Depth run exceeds scanline");
                    if (code > 128) Arrays.fill(scanline[channel], written, written + count, bytes[offset++]);
                    else { System.arraycopy(bytes, offset, scanline[channel], written, count); offset += count; }
                    written += count;
                }
            }
            for (int x = 0; x < width; x++) {
                int exponent = scanline[3][x] & 255;
                // SceneDepth is in R, not luminance; G and B are zero in the native export.
                float value = exponent == 0 ? 0 : (scanline[0][x] & 255) * Math.scalb(1.0f, exponent - 136);
                require(Float.isFinite(value) && value >= 0 && value <= 65504, "Depth exceeds the native half-float capture range");
                depth[y * width + x] = value;
            }
        }
        require(offset == bytes.length, "Unexpected depth trailing bytes");
        return depth;
    }

    private static int imageLine(String line, String tag, String id, String suffix, boolean pending) {
        String[] values = fields(line, tag, 3);
        require(values[1].equals("eve-frame-" + id + "-" + suffix), "Capture filename does not match its generation");
        return (int) integer(values[2], pending ? 0 : 1, pending ? 0 : MAX_IMAGE_BYTES);
    }
    private static String[] fields(String line, String tag, int count) {
        String[] values = line.split(" ", -1);
        require(values.length == count && values[0].equals(tag), "Invalid " + tag + " line");
        for (String value : values) require(!value.isEmpty(), "Empty capture field");
        return values;
    }
    private static long integer(String text, long minimum, long maximum) {
        require(text.matches("[0-9]+"), "Invalid capture integer");
        long value = Long.parseLong(text);
        require(value >= minimum && value <= maximum, "Capture integer exceeds limits");
        return value;
    }
    private static double decimal(String text) {
        require(DECIMAL.matcher(text).matches(), "Invalid capture number");
        double value = Double.parseDouble(text);
        require(Double.isFinite(value), "Capture number must be finite");
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

package org.stellarbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** A bounded one-shot native capture; no packet is executable. */
final class CaptureFrame {
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final long MAX_INTEGER = 9007199254740991L;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    record Position(double x, double y, double z) { }
    record Camera(Position position, double pitch, double yaw, double roll) { }
    record Pawn(Position position, double halfHeight) { }
    record Context(long pawn, long world) { }
    record Link(String session, int ack, double scale, Position feet) { }
    record Metadata(String id, long time, double fov, int colorBytes, int maskBytes, int depthBytes,
                    Camera camera, Pawn pawn, Context context, Link link) { }
    record Loaded(Metadata metadata, byte[] descriptor, byte[] color, byte[] mask, float[] depth) { }

    static Metadata parse(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= 2048, "Capture descriptor exceeds limits");
        for (byte value : bytes)
            require(value == '\n' || value >= 32 && value <= 126, "Capture descriptor must be ASCII with LF endings");
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        require(lines.length == 11 && lines[10].isEmpty(), "Capture descriptor must have exactly ten lines");
        String[] header = fields(lines[0], "SBMC_FRAME", 4);
        require(header[1].equals("1") && header[2].matches("[0-9]+-[0-9]+"), "Unsupported capture header");
        String id = header[2];
        long time = integer(header[3], 0, MAX_INTEGER);
        String[] sequence = id.split("-", -1);
        require(integer(sequence[0], 0, MAX_INTEGER) == time, "Capture ID timestamp mismatch");
        integer(sequence[1], 1, MAX_INTEGER);
        require(lines[1].equals("SIZE 320 180"), "Only 320 by 180 captures are supported");
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
        long ack = integer(link[2], unlinked ? 0 : 1, Integer.MAX_VALUE);
        require(decimal(link[3]) > 0, "Invalid capture scale");
        double x = decimal(link[4]), y = decimal(link[5]), z = decimal(link[6]);
        require(!unlinked || ack == 0 && x == 0 && y == 0 && z == 0, "Unlinked capture has bridge coordinates");
        int color = imageLine(lines[6], "COLOR", id, "color.png");
        int mask = imageLine(lines[7], "MASK", id, "mask.png");
        int depth = imageLine(lines[8], "DEPTH", id, "depth.hdr");
        require(fields(lines[9], "END", 2)[1].equals(id), "Capture footer mismatch");
        return new Metadata(id, time, fov, color, mask, depth,
                new Camera(new Position(decimal(camera[1]), decimal(camera[2]), decimal(camera[3])),
                        decimal(camera[4]), decimal(camera[5]), decimal(camera[6])),
                new Pawn(new Position(decimal(pawn[1]), decimal(pawn[2]), decimal(pawn[3])), decimal(pawn[4])),
                new Context(integer(context[1], 1, MAX_INTEGER), integer(context[2], 1, MAX_INTEGER)),
                new Link(link[1], (int) ack, decimal(link[3]), new Position(x, y, z)));
    }

    static boolean fresh(long time, long now) {
        return time >= 0 && time <= MAX_INTEGER && now >= 0 && now <= MAX_INTEGER && now - time >= -2 && now - time <= 30;
    }

    static int previewAlpha(int inverseAlpha) {
        int alpha = 255 - inverseAlpha;
        return alpha >= 128 ? alpha : 0;
    }

    static Loaded read(Path directory, long now) throws IOException {
        byte[] descriptor = readFile(directory, "eve-frame.txt", 2048);
        Metadata metadata = parse(descriptor);
        require(fresh(metadata.time, now), "Capture is older than 30 seconds or has a future timestamp");
        String prefix = "eve-frame-" + metadata.id + "-";
        byte[] color = readFile(directory, prefix + "color.png", MAX_IMAGE_BYTES);
        byte[] mask = readFile(directory, prefix + "mask.png", MAX_IMAGE_BYTES);
        byte[] depth = readFile(directory, prefix + "depth.hdr", MAX_IMAGE_BYTES);
        require(color.length == metadata.colorBytes && mask.length == metadata.maskBytes && depth.length == metadata.depthBytes,
                "Capture image size does not match its descriptor");
        validatePng(color); validatePng(mask);
        float[] decodedDepth = decodeDepth(depth);
        verifyUnchanged(directory, descriptor);
        return new Loaded(metadata, descriptor, color, mask, decodedDepth);
    }

    static void verifyUnchanged(Path directory, byte[] descriptor) throws IOException {
        require(Arrays.equals(descriptor, readFile(directory, "eve-frame.txt", 2048)), "Capture changed during loading; retry");
    }

    private static byte[] readFile(Path directory, String name, int limit) throws IOException {
        directory = directory.toAbsolutePath().normalize();
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Capture directory must not contain symlinks or non-directories");
        }
        Path file = directory.resolve(name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Capture is unavailable or is not a regular file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(limit + 1);
            require(data.length <= limit, "Capture file exceeds limits");
            return data;
        }
    }

    private static void validatePng(byte[] bytes) {
        require(bytes.length >= 45, "Truncated capture PNG");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        require(input.getLong() == 0x89504e470d0a1a0aL && input.getInt() == 13 && input.getInt() == 0x49484452,
                "Invalid capture PNG header");
        require(input.getInt() == 320 && input.getInt() == 180 && input.get() == 8 && input.get() == 6,
                "Capture PNG must be 320 by 180 RGBA8");
        boolean pixels = false, ended = false;
        input.position(8);
        while (input.remaining() >= 12) {
            int length = input.getInt(), type = input.getInt();
            require(length >= 0 && length <= input.remaining() - 4, "Truncated capture PNG chunk");
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

    static float[] decodeDepth(byte[] bytes) {
        require(bytes.length <= MAX_IMAGE_BYTES, "Depth image exceeds limits");
        byte[] header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 180 +X 320\n".getBytes(StandardCharsets.US_ASCII);
        require(bytes.length >= header.length && Arrays.equals(header, Arrays.copyOf(bytes, header.length)), "Invalid native depth header");
        int offset = header.length;
        float[] depth = new float[320 * 180];
        byte[][] scanline = new byte[4][320];
        for (int y = 0; y < 180; y++) {
            require(offset + 4 <= bytes.length && bytes[offset] == 2 && bytes[offset + 1] == 2
                    && bytes[offset + 2] == 1 && bytes[offset + 3] == 64, "Invalid depth scanline");
            offset += 4;
            for (int channel = 0; channel < 4; channel++) {
                int width = 0;
                while (width < 320) {
                    require(offset < bytes.length, "Truncated depth scanline");
                    int code = bytes[offset++] & 255;
                    require(code != 0, "Invalid depth run");
                    int count = code > 128 ? code - 128 : code;
                    require(width + count <= 320 && offset + (code > 128 ? 1 : count) <= bytes.length, "Depth run exceeds scanline");
                    if (code > 128) Arrays.fill(scanline[channel], width, width + count, bytes[offset++]);
                    else { System.arraycopy(bytes, offset, scanline[channel], width, count); offset += count; }
                    width += count;
                }
            }
            for (int x = 0; x < 320; x++) {
                int exponent = scanline[3][x] & 255;
                // SceneDepth is in R, not luminance; G and B are zero in the native export.
                float value = exponent == 0 ? 0 : (scanline[0][x] & 255) * Math.scalb(1.0f, exponent - 136);
                require(Float.isFinite(value) && value >= 0 && value <= 65504, "Depth exceeds the native half-float capture range");
                depth[y * 320 + x] = value;
            }
        }
        require(offset == bytes.length, "Unexpected depth trailing bytes");
        return depth;
    }

    private static int imageLine(String line, String tag, String id, String suffix) {
        String[] values = fields(line, tag, 3);
        require(values[1].equals("eve-frame-" + id + "-" + suffix), "Capture filename does not match its generation");
        return (int) integer(values[2], 1, MAX_IMAGE_BYTES);
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

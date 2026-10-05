package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import javax.imageio.ImageIO;

/** The bounded opaque-cube scene contract, independent of either game's live objects. */
final class NativeScene {
    static final int MAX_BYTES = 1024 * 1024;
    private static final Set<String> MATERIALS = Set.of("stone", "dirt", "grass_block", "white_wool", "bedrock");
    private static final int[][] NORMALS = {{0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0}};
    private static final String DIRECTIONS = "DUNSWE";
    record Binding(String session, int collisionRevision, String dimension, CaptureFrame.Position origin) { }
    record Cell(int x, int y, int z, String material) { }
    record Texture(String base, String overlay, int tint) { }
    record Face(Texture texture, double x, double y, double z, char direction) { }
    record Tile(Texture texture, String digest, byte[] png) { }

    static List<Face> faces(List<Cell> cells, ToIntFunction<Cell> grassTint) {
        Map<String, Cell> occupied = new HashMap<>();
        for (Cell cell : cells) {
            require(MATERIALS.contains(cell.material), "Unsupported native scene block: " + cell.material);
            require(cell.x >= -4 && cell.x <= 4 && cell.y >= -2 && cell.y <= 3 && cell.z >= -4 && cell.z <= 4,
                    "Native scene cell exceeds the bounded patch");
            require(occupied.put(key(cell.x, cell.y, cell.z), cell) == null, "Duplicate native scene cell");
        }
        List<Face> faces = new ArrayList<>();
        for (Cell cell : cells) {
            int tint = cell.material.equals("grass_block") ? grassTint.applyAsInt(cell) & 0xffffff : 0xffffff;
            for (int i = 0; i < NORMALS.length; i++) {
                int[] normal = NORMALS[i];
                if (occupied.containsKey(key(cell.x + normal[0], cell.y + normal[1], cell.z + normal[2]))) continue;
                char direction = DIRECTIONS.charAt(i);
                Texture texture = new Texture(cell.material, null, 0xffffff);
                if (cell.material.equals("grass_block")) texture = switch (direction) {
                    case 'D' -> new Texture("dirt", null, 0xffffff);
                    case 'U' -> new Texture("grass_block_top", null, tint);
                    default -> new Texture("grass_block_side", "grass_block_side_overlay", tint);
                };
                faces.add(new Face(texture, cell.x + normal[0] * 0.5, cell.y + 0.5 + normal[1] * 0.5,
                        cell.z + normal[2] * 0.5, direction));
            }
        }
        require(faces.size() <= 2048, "Native scene exceeds 2048 exposed faces");
        require(faces.stream().map(Face::texture).distinct().count() <= 32, "Native scene exceeds 32 texture/tint combinations");
        return List.copyOf(faces);
    }

    static byte[] tile(byte[] baseBytes, byte[] overlayBytes, int tint) throws IOException {
        BufferedImage base = source(baseBytes), overlay = overlayBytes == null ? null : source(overlayBytes);
        BufferedImage result = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int pixel = base.getRGB(x * base.getWidth() / 256, y * base.getHeight() / 256);
                require(pixel >>> 24 == 255, "Native scene requires an opaque base texture");
                if (overlay == null) pixel = tint(pixel, tint);
                else {
                    int top = overlay.getRGB(x * overlay.getWidth() / 256, y * overlay.getHeight() / 256);
                    int alpha = top >>> 24;
                    top = tint(top, tint);
                    int blended = 0xff000000;
                    for (int shift = 0; shift <= 16; shift += 8)
                        blended |= (((top >>> shift & 255) * alpha + (pixel >>> shift & 255) * (255 - alpha) + 127) / 255) << shift;
                    pixel = blended;
                }
                result.setRGB(x, y, pixel);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        require(ImageIO.write(result, "PNG", bytes), "PNG encoder is unavailable");
        require(bytes.size() <= MAX_BYTES, "Native scene tile exceeds 1 MiB");
        return bytes.toByteArray();
    }

    private static BufferedImage source(byte[] bytes) throws IOException {
        require(bytes != null && bytes.length >= 33 && bytes.length <= MAX_BYTES, "Native scene source PNG exceeds limits");
        ByteBuffer header = ByteBuffer.wrap(bytes);
        require(header.getLong() == 0x89504e470d0a1a0aL && header.getInt() == 13 && header.getInt() == 0x49484452,
                "Native scene source is not a PNG");
        int width = header.getInt(), height = header.getInt();
        require(width == height && width > 0 && width <= 256 && (width & (width - 1)) == 0,
                "Native scene textures must be square powers of two, at most 256 pixels");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        require(decoded != null && decoded.getWidth() == width && decoded.getHeight() == height, "Native scene PNG decode failed");
        return decoded;
    }

    private static int tint(int pixel, int tint) {
        int result = 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8)
            result |= (((pixel >>> shift & 255) * (tint >>> shift & 255) + 127) / 255) << shift;
        return result;
    }

    static String filename(Binding binding, String digest) {
        require(binding.session.matches("[0-9a-f]{32}") && digest.matches("[0-9a-f]{64}"), "Invalid scene texture identity");
        return "mc-scene-" + binding.session + "-" + digest + ".png";
    }

    static String body(Binding binding, List<Tile> tiles, List<Face> faces) {
        require(binding.session.matches("[0-9a-f]{32}") && binding.collisionRevision > 0, "Invalid scene binding");
        require(binding.dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "Invalid scene dimension");
        var origin = binding.origin;
        require(Double.isFinite(origin.x()) && Double.isFinite(origin.y()) && Double.isFinite(origin.z()), "Invalid scene origin");
        require(tiles.size() <= 32 && faces.size() <= 2048, "Native scene exceeds texture or face limit");
        Map<String, Integer> digests = new LinkedHashMap<>();
        Map<Texture, Integer> indices = new HashMap<>();
        StringBuilder textures = new StringBuilder();
        for (Tile tile : tiles) {
            require(tile.png.length > 0 && tile.png.length <= MAX_BYTES, "Invalid scene tile byte count");
            Integer index = digests.get(tile.digest);
            if (index == null) {
                index = digests.size();
                digests.put(tile.digest, index);
                textures.append("TEXTURE ").append(index).append(' ').append(filename(binding, tile.digest))
                        .append(' ').append(tile.png.length).append(" 256 256\n");
            }
            require(indices.put(tile.texture, index) == null, "Duplicate scene texture key");
        }
        StringBuilder result = new StringBuilder("DIM ").append(binding.dimension).append("\nORIGIN ")
                .append(origin.x()).append(' ').append(origin.y()).append(' ').append(origin.z())
                .append("\nCOUNTS ").append(digests.size()).append(' ').append(faces.size()).append('\n').append(textures);
        Set<String> usedFaces = new HashSet<>();
        for (Face face : faces) {
            int direction = DIRECTIONS.indexOf(face.direction);
            require(direction >= 0 && indices.containsKey(face.texture), "Invalid scene face texture/direction");
            int[] normal = NORMALS[direction];
            double x = face.x - normal[0] * 0.5, y = face.y - normal[1] * 0.5, z = face.z - normal[2] * 0.5;
            require(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && x >= -4 && x <= 4 && y >= -1.5 && y <= 3.5 && z >= -4 && z <= 4,
                    "Scene face exceeds patch bounds");
            require(usedFaces.add(face.x + ":" + face.y + ":" + face.z + ":" + face.direction), "Duplicate scene face");
            result.append("FACE ").append(indices.get(face.texture)).append(' ').append(face.x).append(' ')
                    .append(face.y).append(' ').append(face.z).append(' ').append(face.direction).append('\n');
        }
        return result.toString();
    }

    static String encode(Binding binding, int revision, long time, List<Tile> tiles, List<Face> faces) {
        require(revision > 0 && time >= 0 && time <= 9007199254740991L, "Invalid scene revision/time");
        String packet = "SBMC_SCENE 1 " + binding.session + " " + revision + " " + time + " " + binding.collisionRevision + "\n"
                + body(binding, tiles, faces) + "END " + binding.session + " " + revision + "\n";
        require(packet.getBytes(StandardCharsets.US_ASCII).length <= MAX_BYTES, "Native scene descriptor exceeds 1 MiB");
        return packet;
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String key(int x, int y, int z) { return x + ":" + y + ":" + z; }
    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}

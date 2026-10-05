package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

public final class NativeSceneTest {
    static final String SESSION = "0123456789abcdef0123456789abcdef";
    static final NativeScene.Binding BINDING = new NativeScene.Binding(SESSION, 1, "minecraft:overworld",
            new CaptureFrame.Position(0.5, 64, 0.5));

    public static void main(String[] args) throws Exception {
        var stone = new NativeScene.Cell(0, 0, 0, "stone");
        var faces = NativeScene.faces(List.of(stone), cell -> 0xffffff);
        assert faces.size() == 6;
        var top = faces.stream().filter(face -> face.direction() == 'U').findFirst().orElseThrow();
        assert top.x() == 0 && top.y() == 1 && top.z() == 0 : "FACE must name the geometric face center";
        var pair = NativeScene.faces(List.of(stone, new NativeScene.Cell(1, 0, 0, "white_wool")), cell -> 0xffffff);
        assert pair.size() == 10 : "Shared opaque faces must be removed";
        var grass = NativeScene.faces(List.of(new NativeScene.Cell(0, 0, 0, "grass_block")), cell -> 0x80ff40);
        assert grass.stream().filter(face -> face.direction() == 'U').findFirst().orElseThrow().texture()
                .equals(new NativeScene.Texture("grass_block_top", null, 0x80ff40));
        assert grass.stream().filter(face -> face.direction() == 'D').findFirst().orElseThrow().texture().base().equals("dirt");
        assert grass.stream().filter(face -> face.direction() == 'N').findFirst().orElseThrow().texture().overlay()
                .equals("grass_block_side_overlay");
        rejects(() -> NativeScene.faces(List.of(new NativeScene.Cell(0, 0, 0, "glass")), cell -> 0xffffff));
        rejects(() -> NativeScene.faces(List.of(stone, stone), cell -> 0xffffff));
        rejects(() -> NativeScene.faces(List.of(new NativeScene.Cell(5, 0, 0, "stone")), cell -> 0xffffff));

        byte[] base = png(2, new int[] {0xff102030, 0xff405060, 0xff708090, 0xffa0b0c0});
        byte[] scaled = NativeScene.tile(base, null, 0xffffff);
        var image = ImageIO.read(new java.io.ByteArrayInputStream(scaled));
        assert image.getWidth() == 256 && image.getHeight() == 256;
        assert image.getRGB(127, 127) == 0xff102030 && image.getRGB(128, 127) == 0xff405060;
        assert image.getRGB(127, 128) == 0xff708090 && image.getRGB(255, 255) == 0xffa0b0c0;
        byte[] overlay = png(1, new int[] {0x80ffffff});
        image = ImageIO.read(new java.io.ByteArrayInputStream(NativeScene.tile(png(1, new int[] {0xff204060}), overlay, 0x80ff40)));
        assert image.getRGB(0, 0) == 0xff50a050 : "Grass side must tint the overlay only, then alpha-composite";
        image = ImageIO.read(new java.io.ByteArrayInputStream(NativeScene.tile(png(1, new int[] {0xff808080}), null, 0x80ff40)));
        assert image.getRGB(0, 0) == 0xff408020;
        rejects(() -> NativeScene.tile(png(3, new int[9]), null, 0xffffff));
        rejects(() -> NativeScene.tile(png(1, new int[] {0x00112233}), null, 0xffffff));

        var tile = new NativeScene.Tile(faces.getFirst().texture(), "a".repeat(64), new byte[1024]);
        String packet = NativeScene.encode(BINDING, 1, 1791110000, List.of(tile), faces);
        assert packet.equals(Files.readString(Path.of("protocol/fixtures/native-scene.txt")))
                : "Java and Lua must share the same synthetic wire fixture";
        rejects(() -> NativeScene.encode(BINDING, 0, 1791110000, List.of(tile), faces));
        rejects(() -> NativeScene.encode(BINDING, 1, 1791110000, List.of(tile), java.util.Collections.nCopies(2049, top)));
        assert packet.getBytes(StandardCharsets.US_ASCII).length < 1024 * 1024;
        System.out.println("Native scene geometry, tile and wire tests passed");
    }

    static byte[] png(int size, int[] pixels) throws Exception {
        var source = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, size, size, pixels, 0, size);
        var out = new ByteArrayOutputStream();
        ImageIO.write(source, "PNG", out);
        return out.toByteArray();
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid native scene accepted"); }
        catch (IllegalArgumentException | java.io.IOException expected) { }
    }
    private interface Action { void run() throws Exception; }
}

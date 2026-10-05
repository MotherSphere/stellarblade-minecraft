package org.stellarbridge;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public final class NativeSceneFilesTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("native-scene-files-");
        var files = new NativeSceneFiles(directory);
        var resources = Map.of("stone", NativeSceneTest.png(1, new int[] {0xff808080}),
                "white_wool", NativeSceneTest.png(1, new int[] {0xffffffff}));
        var stone = NativeScene.faces(List.of(new NativeScene.Cell(0, 0, 0, "stone")), cell -> 0xffffff);
        var wool = NativeScene.faces(List.of(new NativeScene.Cell(0, 0, 0, "white_wool")), cell -> 0xffffff);
        try {
            try (var publication = files.stage(NativeSceneTest.BINDING, stone, resources, 100)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).startsWith("SBMC_SCENE 1 " + NativeSceneTest.SESSION + " 1 100 1\n");
            try (var publication = files.stage(NativeSceneTest.BINDING, stone, resources, 101)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 1 101 1\n");
            try (var publication = files.stage(NativeSceneTest.BINDING, wool, resources, 102)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 2 102 1\n")
                    : "A material-only change must advance visual revision without changing collision revision";
            try (var publication = files.stage(NativeSceneTest.BINDING, stone, resources, 103)) { }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 2 102 1\n")
                    : "A cancelled replacement must not publish";
            try (var paths = Files.list(directory)) { assert paths.count() == 2 : "Only active descriptor/tile may remain"; }
            var collisionChanged = new NativeScene.Binding(NativeSceneTest.SESSION, 2, "minecraft:overworld", NativeSceneTest.BINDING.origin());
            try (var publication = files.stage(collisionChanged, wool, resources, 103)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 3 103 2\n")
                    : "A collision revision must publish a newly bound visual revision";
            var next = new NativeScene.Binding("f".repeat(32), 1, "minecraft:overworld", NativeSceneTest.BINDING.origin());
            try (var publication = files.stage(next, stone, resources, 104)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).contains("SBMC_SCENE 1 " + "f".repeat(32) + " 1 104 1\n");
            Files.writeString(directory.resolve("unrelated.txt"), "preserve");
            files.stop();
            assert Files.readString(directory.resolve("unrelated.txt")).equals("preserve");
            try (var paths = Files.list(directory)) { assert paths.count() == 1; }
            try (var publication = files.stage(next, wool, resources, 105)) { publication.commit(); }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 2 105 1\n")
                    : "A temporary export stop must not roll a live session's visual revision backward";
            java.nio.file.Path blocked;
            try (var paths = Files.list(directory)) {
                blocked = paths.filter(path -> path.toString().endsWith(".png")).findFirst().orElseThrow();
            }
            Files.delete(blocked);
            Files.createDirectory(blocked); // A filesystem replacement simulates a cleanup failure.
            try (var publication = files.stage(next, stone, resources, 106)) {
                publication.commit();
            } catch (java.io.IOException expected) { }
            assert Files.readString(directory.resolve("native-scene.txt")).contains(" 3 106 1\n");
            try {
                files.stage(next, wool, resources, 107);
                throw new AssertionError("Cleanup failure must block further allocations");
            } catch (java.io.IOException expected) { }
            try (var paths = Files.list(directory)) { assert paths.count() == 4; }
            Files.delete(blocked);
            files.stop(); // Must still remember and discharge the failed deletion obligation.
            try (var paths = Files.list(directory)) { assert paths.count() == 1; }
        } finally {
            files.stop();
            Files.deleteIfExists(directory.resolve("unrelated.txt"));
            Files.delete(directory);
        }
        System.out.println("Native scene publication and revision tests passed");
    }
}

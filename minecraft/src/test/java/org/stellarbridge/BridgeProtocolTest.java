package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import static org.stellarbridge.BridgeProtocol.*;

/** Pure JDK assertion runner; no Minecraft or test framework required. */
public final class BridgeProtocolTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Malformed packet was accepted");
    }
    private static Terrain terrain(List<Box> boxes) {
        return new Terrain("0123456789abcdef0123456789abcdef", 7, 1791110000,
            "minecraft:overworld", 0.5, 64, -0.5, new Box(-4.5,-2,-4.5,4.5,4,4.5), boxes);
    }
    private static void verifyEve(Eve eve) {
        check(eve.seq() == 9 && eve.ack() == 7 && eve.time() == 1791110001, "Sequence/time mismatch");
        check(eve.session().equals("0123456789abcdef0123456789abcdef") && eve.state().equals("entered"), "Identity mismatch");
        check(eve.x() == -1.25 && eve.y() == 0.05 && eve.z() == 2, "Position mismatch");
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        String fixture = Files.readString(root.resolve("protocol/fixtures/eve.txt"));
        verifyEve(parseEve(fixture));
        verifyEve(parseEve(fixture.replace("0.05", "5e-2")));
        for (String invalid : List.of(
            fixture.stripTrailing(), fixture + "\n", fixture.replace("\n", "\r\n"),
            fixture.replace("END 9", "END 8"), fixture.replace("SEQ 9", "SEQ 0"),
            fixture.replace("SEQ 9", "SEQ 2147483648"), fixture.replace("entered 7", "entered 0"),
            fixture.replace("entered", "flying"), fixture.replace("-1.25", "NaN"),
            fixture.replace("-1.25", "1e999"), fixture.replace("-1.25", "65"),
            fixture.replace("-1.25", "0x1.0p0"), fixture.replace("SEQ ", "SEQ  "),
            fixture.replace("SESSION 0", "SESSION G"), fixture.replace("1791110001", "-1"),
            fixture.replace("1791110001", "9007199254740992"), "x".repeat(65537))) {
            rejects(() -> parseEve(invalid));
        }
        rejects(() -> parseEve(null));
        Terrain terrain = terrain(List.of(new Box(-4.5,-1,-4.5,4.5,0,4.5), new Box(1,0,-1,2,1.5,0)));
        String encoded = encodeTerrain(terrain);
        check(encoded.startsWith("SBMC 2 TERRAIN\n") && encoded.endsWith("END 7\n"), "Framing mismatch");
        check(encoded.contains("COUNT 2\n"), "Box count mismatch");
        rejects(() -> encodeTerrain(terrain(List.of(new Box(1,0,0,1,1,1)))));
        rejects(() -> encodeTerrain(terrain(List.of(new Box(-5,0,0,1,1,1)))));
        rejects(() -> encodeTerrain(terrain(List.of(new Box(0,0,0,Double.NaN,1,1)))));
        // Combat events for Stellar Blade follow the shared fixture; bad events, yaws and sessions are refused.
        assert encodeCombat("a".repeat(32), 7, 1760000000000L, "hit", -135).equals(Files.readString(root.resolve("protocol/fixtures/combat-event.txt")));
        assert encodeCombat("a".repeat(32), 1, 0, "perfect_dodge", Double.NaN).contains("\nEVENT perfect_dodge none\n");
        assert encodeCombat("a".repeat(32), 2, 0, "parry", 0).startsWith("SBMC_COMBAT 2\n")
                && encodeCombat("a".repeat(32), 3, 0, "guard_strong", 0).contains("\nEVENT guard_strong 0.0\n")
                && encodeCombat("a".repeat(32), 4, 0, "hit_strong", 90).contains("\nEVENT hit_strong 90.0\n");
        rejects(() -> encodeCombat("a".repeat(32), 1, 0, "dodge", 0));
        rejects(() -> encodeCombat("a".repeat(32), 1, 0, "dodge_strong", 0));
        rejects(() -> encodeCombat("a".repeat(32), 1, 0, "hit", 181));
        rejects(() -> encodeCombat("A".repeat(32), 1, 0, "hit", 0));
        rejects(() -> encodeCombat("a".repeat(32), 0, 0, "hit", 0));
        // Lock-on targets for Stellar Blade follow the shared fixture; none releases; out-of-range targets are refused.
        assert encodeLock("a".repeat(32), 3, 1760000000000L, new double[] {2.5, 1, -3.25}).equals(Files.readString(root.resolve("protocol/fixtures/lock.txt")));
        assert encodeLock("a".repeat(32), 4, 0, null).contains("\nTARGET none\n");
        rejects(() -> encodeLock("a".repeat(32), 1, 0, new double[] {5000, 0, 0}));
        rejects(() -> encodeLock("a".repeat(32), 1, 0, new double[] {Double.NaN, 0, 0}));
        // Mob walls for Stellar Blade follow the shared fixture; too many, too thin, out-of-range boxes and bad sessions are refused.
        List<Box> mobs = List.of(new Box(1.2, 0, -0.3, 1.8, 1.95, 0.3), new Box(-3.5, 0, 2, -2.1, 0.9, 3.4));
        assert encodeMobs("a".repeat(32), 5, 1760000000000L, mobs).equals(Files.readString(root.resolve("protocol/fixtures/mobs.txt")));
        assert encodeMobs("a".repeat(32), 1, 0, List.of()).endsWith("\nCOUNT 0\nEND 1\n");
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, Collections.nCopies(9, mobs.get(0))));
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, List.of(new Box(0, 0, 0, 0.005, 1, 1))));
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, List.of(new Box(0, 0, 0, 33, 1, 1))));
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, List.of(new Box(0, 0, 0, Double.NaN, 1, 1))));
        rejects(() -> encodeMobs("A".repeat(32), 1, 0, mobs));
        rejects(() -> encodeMobs("a".repeat(32), 0, 0, mobs));
        rejects(() -> encodeTerrain(terrain(List.of(new Box(0,0,0,1,1,1), new Box(0,0,0,1,1,1)))));
        // The whole box budget fits one 64 KiB packet even with sixteenth-block coordinates on every side.
        List<Box> many = new java.util.ArrayList<>();
        for (int i = 0; i <= MAX_BOXES; i++) {
            double x = -16.4375 + i % 32, y = -3.9375 + i / 1024 * 2, z = -16.4375 + i / 32 % 32;
            many.add(new Box(x, y, z, x + 0.875, y + 1.875, z + 0.875));
        }
        Box large = new Box(-16.5, -4, -16.5, 16.5, 8, 16.5);
        check(encodeTerrain(new Terrain(terrain.session(), 7, 1791110000, "minecraft:overworld", 0.5, 64, -0.5, large,
            many.subList(0, MAX_BOXES))).contains("COUNT " + MAX_BOXES + "\n"), "The full box budget must fit one packet");
        rejects(() -> encodeTerrain(new Terrain(terrain.session(), 7, 1791110000, "minecraft:overworld", 0.5, 64, -0.5, large, many)));
        // Randomly offset shapes (bamboo) have long coordinates: a ten-thousandth of a block keeps the budget in one packet.
        List<Box> offset = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_BOXES; i++) {
            double x = -16.17708332836628 + i % 32, y = -3.9 + i / 1024 * 2, z = -16.27708332986396 + i / 32 % 32;
            offset.add(new Box(x, y, z, x + 0.12500000000000003, y + 1.0000000000000002, z + 0.12500000000000003));
        }
        String bamboo = encodeTerrain(new Terrain(terrain.session(), 7, 1791110000, "minecraft:overworld", 0.5, 64, -0.5, large, offset));
        check(bamboo.contains("BOX -16.1771 -3.9 -16.2771 -16.0521 -2.9 -16.1521\n"), "Coordinates at a ten-thousandth of a block");
        rejects(() -> encodeTerrain(terrain(List.of(new Box(0, 0, 0, 0.00004, 1, 1))))); // Collapsed by rounding.
        rejects(() -> encodeTerrain(new Terrain("BAD",7,1,"minecraft:overworld",0,0,0,terrain.bounds(),List.of())));
        rejects(() -> encodeTerrain(new Terrain(terrain.session(),0,1,"minecraft:overworld",0,0,0,terrain.bounds(),List.of())));
        rejects(() -> encodeTerrain(new Terrain(terrain.session(),7,1,"Minecraft:overworld",0,0,0,terrain.bounds(),List.of())));
        rejects(() -> encodeTerrain(new Terrain(terrain.session(),7,1,"minecraft:overworld",0,4097,0,terrain.bounds(),List.of())));
        check(fresh(100,105) && fresh(102,100) && !fresh(100,106) && !fresh(103,100), "Freshness boundary mismatch");
        check(!fresh(Long.MAX_VALUE,Long.MIN_VALUE), "Freshness overflow");
        if (args.length > 1) {
            Path exchange = Path.of(args[1]);
            Files.createDirectories(exchange);
            Files.writeString(exchange.resolve("terrain-java.txt"), encoded);
            if (Files.exists(exchange.resolve("eve-lua.txt"))) verifyEve(parseEve(Files.readString(exchange.resolve("eve-lua.txt"))));
        }
        System.out.println("PASS protocol Java: valid EVE, malformed snapshots, terrain validation, mob walls, freshness");
    }
}

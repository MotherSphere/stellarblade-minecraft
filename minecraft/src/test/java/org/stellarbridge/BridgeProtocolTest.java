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
        check(encoded.startsWith("SBMC 1 TERRAIN\n") && encoded.endsWith("END 7\n"), "Framing mismatch");
        check(encoded.contains("COUNT 2\n"), "Box count mismatch");
        rejects(() -> encodeTerrain(terrain(List.of(new Box(1,0,0,1,1,1)))));
        rejects(() -> encodeTerrain(terrain(List.of(new Box(-5,0,0,1,1,1)))));
        rejects(() -> encodeTerrain(terrain(List.of(new Box(0,0,0,Double.NaN,1,1)))));
        rejects(() -> encodeTerrain(terrain(Collections.nCopies(257,new Box(0,0,0,1,1,1)))));
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
        System.out.println("PASS protocol Java: valid EVE, malformed snapshots, terrain validation, freshness");
    }
}

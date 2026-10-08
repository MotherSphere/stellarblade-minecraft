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
            fixture.replace("-1.25", "1e999"), fixture.replace("-1.25", "2048.001"),
            fixture.replace("-1.25", "0x1.0p0"), fixture.replace("SEQ ", "SEQ  "),
            fixture.replace("SESSION 0", "SESSION G"), fixture.replace("1791110001", "-1"),
            fixture.replace("1791110001", "9007199254740992"), fixture.replace("SBMC 2", "SBMC 1"), "x".repeat(65537))) {
            rejects(() -> parseEve(invalid));
        }
        rejects(() -> parseEve(null));
        check(parseEve(fixture.replace("-1.25", "-2048")).x() == -2048, "EVE may travel MAX_REACH blocks from the origin");
        Terrain terrain = terrain(List.of(new Box(-4.5,-1,-4.5,4.5,0,4.5), new Box(1,0,-1,2,1.5,0)));
        String encoded = encodeTerrain(terrain);
        check(encoded.startsWith("SBMC 3 TERRAIN\n") && encoded.endsWith("END 7\n"), "Framing mismatch");
        check(encoded.contains("COUNT 2\n"), "Box count mismatch");
        check(encoded.contains("\nBOUNDS -4.5 -2.0 -4.5 4.5 4.0 4.5\n") && encoded.contains("\nBOX 0.0 1.0 0.0 9.0 2.0 9.0\n")
            && encoded.contains("\nBOX 5.5 2.0 3.5 6.5 3.5 4.5\n"), "Boxes are relative to the window's minimum corner");
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
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, List.of(new Box(0, 0, 0, 2049, 1, 1))));
        assert encodeMobs("a".repeat(32), 1, 0, List.of(new Box(2000, 70, -2000, 2000.6, 71.8, -1999.4)))
                .contains("\nMOB 2000.000 70.000 -2000.000 2000.600 71.800 -1999.400\n") : "Mobs travel with the window";
        rejects(() -> encodeMobs("a".repeat(32), 1, 0, List.of(new Box(0, 0, 0, Double.NaN, 1, 1))));
        rejects(() -> encodeMobs("A".repeat(32), 1, 0, mobs));
        rejects(() -> encodeMobs("a".repeat(32), 0, 0, mobs));
        // Session requests follow the shared fixture; Stellar Blade's answers parse from theirs; anything else is refused.
        assert encodeRequest("a".repeat(32), 4, 1760000000000L, "import").equals(Files.readString(root.resolve("protocol/fixtures/request.txt")));
        assert encodeRequest("a".repeat(32), 5, 0, "return").contains("\nACTION return\nEND 5\n");
        rejects(() -> encodeRequest("a".repeat(32), 1, 0, "teleport"));
        rejects(() -> encodeRequest("A".repeat(32), 1, 0, "enter"));
        rejects(() -> encodeRequest("a".repeat(32), 0, 0, "enter"));
        rejects(() -> encodeRequest("a".repeat(32), 1, -1, "enter"));
        String ack = Files.readString(root.resolve("protocol/fixtures/request-ack.txt"));
        RequestAck answer = parseRequestAck(ack);
        assert answer.equals(new RequestAck("a".repeat(32), 4, "import", "ok", "Imported 12 Minecraft collision boxes.")) : answer;
        assert parseRequestAck(ack.replace("RESULT ok", "RESULT refused")).result().equals("refused");
        assert parseRequestAck(ack.replace("Imported 12 Minecraft collision boxes.", "x".repeat(400))).message().length() == 400;
        for (String invalid : List.of(ack.replace("END 4", "END 5"), ack.replace("RESULT ok", "RESULT maybe"),
                ack.replace("ACTION import", "ACTION teleport"), ack.replace("SBMC_REQUEST_ACK 1", "SBMC_REQUEST_ACK 2"),
                ack.replace("SESSION a", "SESSION A"), ack.replace("Imported 12 Minecraft collision boxes.", ""),
                ack.replace("Imported 12 Minecraft collision boxes.", "x".repeat(401)), ack.replace("Imported", "Importedé"),
                ack.replace("\n", "\r\n"), ack.stripTrailing(), ack + "\n", ack.replace("SEQ 4", "SEQ 0").replace("END 4", "END 0")))
            rejects(() -> parseRequestAck(invalid));
        rejects(() -> parseRequestAck(null));

        // Wardrobe: the request the native adapter parses and its answer, both shared fixtures.
        String guid = "0000000100000002000000030000000a";
        check(encodeWardrobeRequest("a".repeat(32), 6, 1760000000000L, "equip", 5, guid)
                .equals(Files.readString(root.resolve("protocol/fixtures/wardrobe-request.txt"))), "Wardrobe request fixture");
        check(encodeWardrobeRequest("a".repeat(32), 1, 0, "list", 0, null).contains("\nOP list\nSLOT 0\nITEM -\nEND 1\n"), "List request");
        check(encodeWardrobeRequest("a".repeat(32), 999999999999999L, 0, "unequip", 17, null).contains("\nSLOT 17\nITEM -\n"), "Unequip request");
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "list", 5, null));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "list", 0, guid));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "equip", 5, null));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "equip", 18, guid));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "equip", 0, guid));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "equip", 5, "0".repeat(32)));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "equip", 5, guid.toUpperCase()));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "unequip", 10, guid));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1, 0, "drop", 10, null));
        rejects(() -> encodeWardrobeRequest("a".repeat(32), 1000000000000000L, 0, "list", 0, null)); // 16 digits.
        rejects(() -> encodeWardrobeRequest("A".repeat(32), 1, 0, "list", 0, null));
        String wardrobe = Files.readString(root.resolve("protocol/fixtures/wardrobe.txt"));
        WardrobeAnswer listed = parseWardrobe(wardrobe);
        check(listed.session().equals("a".repeat(32)) && listed.seq() == 6 && listed.time() == 1760000000500L
                && listed.result().equals("equipped") && listed.reason() == null && listed.items().size() == 4, "Wardrobe fixture");
        check(listed.items().get(0).equals(new WardrobeItem(4000001, 4, guid, 0, 0, 5)), "Worn suit");
        check(listed.items().get(2).equals(new WardrobeItem(2100003, 21, "000000010000000200000003000000ff", 0, 2, 8)), "Hair with its colour");
        check(listed.items().get(3).level() == 3 && listed.items().get(3).slot() == 6, "Exospine level and slot");
        WardrobeAnswer refused = parseWardrobe("SBMC_WARDROBE 1\nSESSION " + "b".repeat(32) + "\nSEQ 2\nTIME 0\nRESULT refused not-playing\nCOUNT 0\nEND 2\n");
        check(refused.result().equals("refused") && refused.reason().equals("not-playing") && refused.items().isEmpty(), "Refusal without items");
        StringBuilder full = new StringBuilder("SBMC_WARDROBE 1\nSESSION " + "a".repeat(32) + "\nSEQ 999999999999999\nTIME 9007199254740991\nRESULT listed\nCOUNT 1024\n");
        for (int i = 0; i < 1024; i++) full.append("ITEM 4294967295 28 ").append(String.format("%032x", i + 1)).append(" 9999 99 17\n");
        String widest = full.append("END 999999999999999\n").toString();
        check(widest.length() <= 65536 && parseWardrobe(widest).items().size() == 1024, "1024 widest lines fit 64 KiB");
        for (String invalid : List.of(wardrobe.replace("SBMC_WARDROBE 1", "SBMC_WARDROBE 2"), wardrobe.replace("END 6", "END 7"),
                wardrobe.replace("COUNT 4", "COUNT 5"), wardrobe.replace("COUNT 4", "COUNT 3"), wardrobe.replace("RESULT equipped", "RESULT maybe"),
                wardrobe.replace("RESULT equipped", "RESULT refused"), wardrobe.replace("RESULT equipped", "RESULT refused lost"),
                wardrobe.replace("RESULT equipped", "RESULT equipped now"),
                wardrobe.replace("RESULT equipped", "RESULT equipped combat"), wardrobe.replace("ITEM 4000001", "ITEM 4294967296"), wardrobe.replace(" 0 2 8\n", " 0 100 8\n"),
                wardrobe.replace(" 3 0 6\n", " 10000 0 6\n"), wardrobe.replace(" 0 2 8\n", " 0 2 18\n"),
                wardrobe.replace("000000ff", "000000FF"), wardrobe.replace("0000abcd 3", "0000abc 3"), wardrobe.replace("ITEM 4000002 4", "ITEM 4000002"),
                wardrobe.replace("SESSION a", "SESSION A"), wardrobe.replace("\n", "\r\n"), wardrobe.stripTrailing(), wardrobe + "\n",
                wardrobe.replace("SEQ 6", "SEQ 0").replace("END 6", "END 0"), wardrobe.replace("ITEM 4000001", "ITEM -1"), wardrobe.replace("ITEM 4000002 4 ", "ITEM 4000002 5 "),
                "SBMC_WARDROBE 1\nSESSION " + "a".repeat(32) + "\nSEQ 1\nTIME 0\nRESULT listed\nCOUNT 1025\nEND 1\n", "x".repeat(65537)))
            rejects(() -> parseWardrobe(invalid));
        rejects(() -> parseWardrobe(null));
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
        check(bamboo.contains("BOX 0.3229 0.1 0.2229 0.4479 1.1 0.3479\n"), "Coordinates at a ten-thousandth of a block");
        // The window travels up to MAX_REACH from the origin; relative boxes keep the full budget in one packet there.
        Box far = new Box(-2047.5, -1000, -2047.5, -2007.5, -976, -2007.5);
        List<Box> distant = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_BOXES; i++) {
            double x = -2047.4375 + i % 32 * 1.25, z = -2047.4375 + i / 32 * 1.25;
            distant.add(new Box(x, -999.9375, z, x + 0.875, -998.0625, z + 0.875));
        }
        String distantText = encodeTerrain(new Terrain(terrain.session(), 7, 1791110000, "minecraft:overworld", 0.5, 64, -0.5, far, distant));
        check(distantText.contains("\nBOX 0.0625 0.0625 0.0625 0.9375 1.9375 0.9375\n"), "Far boxes stay short");
        rejects(() -> encodeTerrain(new Terrain(terrain.session(), 7, 1791110000, "minecraft:overworld", 0.5, 64, -0.5,
            new Box(2009, 0, 0, 2049, 24, 40), List.of())));
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
            Files.writeString(exchange.resolve("terrain-far-java.txt"), distantText);
            Files.writeString(exchange.resolve("terrain-bamboo-java.txt"), bamboo);
            if (Files.exists(exchange.resolve("eve-lua.txt"))) verifyEve(parseEve(Files.readString(exchange.resolve("eve-lua.txt"))));
        }
        System.out.println("PASS protocol Java: valid EVE, malformed snapshots, terrain validation, mob walls, session requests, wardrobe, freshness");
    }
}

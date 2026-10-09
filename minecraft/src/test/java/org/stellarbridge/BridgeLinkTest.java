package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.math.Vec3d;

/**
 * The link across machines: the client writes every TIME with its own clock whatever the server's, relays each eve.N
 * once and predicts EVE's position as the server's gate will; the server judges a relayed packet by its sender's clock,
 * and one player's relay never takes or skews another's.
 */
public final class BridgeLinkTest {
    private static final String A = "0123456789abcdef0123456789abcdef", B = "fedcba9876543210fedcba9876543210";

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("stellar-link-test-");
        try {
            Path directory = temporary.resolve("bridge");
            Vec3d origin = new Vec3d(100.5, 64, -20.5);
            BridgeLink link = new BridgeLink(new BridgeFiles(directory), A, origin, "minecraft:overworld");
            long server = 1_760_000_000_000L, client = server + 3_600_250; // This PC's clock runs an hour ahead.
            var window = new BridgeProtocol.Box(-20, -8, -20, 20, 16, 20);
            var box = new BridgeProtocol.Box(-1, -1, -1, 1, 0, 1);
            link.terrain(new BridgeProtocol.Terrain(A, 1, server / 1000, "minecraft:overworld", origin.x, origin.y, origin.z,
                    window, List.of(box)), client);
            assert time(directory, "terrain.txt") == client / 1000 : "terrain.txt carries this PC's clock, not the server's";
            link.terrain(new BridgeProtocol.Terrain(B, 2, 0, "minecraft:overworld", 0, 64, 0, window, List.of()), client);
            link.terrain(new BridgeProtocol.Terrain(A, 1, 0, "minecraft:overworld", 0, 64, 0, window, List.of()), client);
            assert link.revision() == 1 && Files.readString(directory.resolve("terrain.txt")).contains("COUNT 1\n")
                    : "Another session's snapshot or an old revision never replaces the link's";
            for (int tick = 1; tick <= 3; tick++) link.tick(client + tick * 1000L);
            assert time(directory, "terrain.txt") == client / 1000 : "No heartbeat before the 4th tick";
            link.tick(client + 4000);
            assert time(directory, "terrain.txt") == (client + 4000) / 1000 : "The heartbeat re-stamps every 4 ticks";

            // Lua stamps eve.N with this PC's clock; the client relays it once and moves EVE as the server will.
            long now = client + 4050;
            String eve = "SBMC 2 EVE\nSESSION " + A + "\nSEQ 7\nTIME " + now / 1000 + "\nSTATE entered 1\nPOS 1.5 0 -2.25\nEND 7\n";
            Files.writeString(directory.resolve("eve.0"), eve);
            assert eve.equals(link.tick(now)) : "A new packet is relayed as Lua wrote it";
            assert link.position().equals(origin.add(1.5, 0, -2.25)) : link.position();
            assert link.tick(now + 50) == null && link.position() != null : "Relayed once, still predicted";

            // The server's clock is an hour behind: it judges TIME by the sender's clock from the payload's sending time.
            long arrival = now - 3_600_250 + 40; // 40 ms in transit.
            EveRelay relay = new EveRelay(A);
            assert relay.receive(eve, now, arrival) && relay.latest().seq() == 7;
            PoseGate gate = new PoseGate(A);
            gate.published(1, window);
            assert gate.accept(relay.latest(), 1, EveRelay.clientSeconds(arrival + 500, relay.clock())) != null
                    : "Fresh by the client's clock";
            assert gate.accept(relay.latest(), 1, arrival / 1000) == null : "The server's own clock would call it an hour old";
            assert gate.accept(relay.latest(), 1, EveRelay.clientSeconds(arrival + 6000, relay.clock())) == null
                    : "Still stale 6 s later, by the client's clock";

            // Per-player isolation: B's relay refuses A's session; B's clock never shifts A's.
            EveRelay other = new EveRelay(B);
            assert !other.receive(eve, now, arrival) && other.latest() == null : "A's packet relayed by B never drives B's session";
            long clock = relay.clock();
            assert other.receive(eve.replace(A, B), arrival - 5000, arrival) && other.clock() == -5000 && relay.clock() == clock;
            assert !relay.receive("SBMC 2 EVE\nmalformed\n", 0, arrival) && relay.latest().seq() == 7 && relay.clock() == clock
                    : "A malformed relay changes nothing";

            // Mob walls, verdicts and the lock-on target carry this PC's clock in milliseconds.
            link.mobs(List.of(box), now + 100);
            assert time(directory, "eve-mobs.txt") == now + 100 && Files.readString(directory.resolve("eve-mobs.txt")).contains("COUNT 1\n");
            link.verdict(BridgeProtocol.COMBAT_EVENTS.indexOf("parry"), 90, now + 200);
            String combat = Files.readString(directory.resolve("eve-combat.txt"));
            assert time(directory, "eve-combat.txt") == now + 200 && combat.contains("EVENT parry 90.0\n") : combat;
            link.lock(new Vec3d(101.5, 65, -20.5), now + 300);
            assert time(directory, "eve-lock.txt") == now + 300
                    && Files.readString(directory.resolve("eve-lock.txt")).contains("TARGET 1.000 1.000 0.000\n");
            link.mobs(List.of(), now + 400);
            String cleared = Files.readString(directory.resolve("eve-mobs.txt"));
            assert cleared.contains("COUNT 0\n") : cleared;
            for (int tick = 0; tick < 8; tick++) link.tick(now + 400);
            assert Files.readString(directory.resolve("eve-mobs.txt")).equals(cleared) : "An empty list is written once";

            // Session requests stay on this PC: one at a time, and an unanswered one frees the next after 5 s.
            assert link.request("import", now, 0) == null && Files.readString(directory.resolve("eve-request.txt")).contains("ACTION import\n");
            assert link.request("enter", now, 1) != null && link.answer(1_000_000_000L) == null;
            Files.writeString(directory.resolve("eve-request-ack.txt"), "SBMC_REQUEST_ACK 1\nSESSION " + A
                    + "\nSEQ 1\nACTION import\nRESULT ok\nMESSAGE imported\nEND 1\n");
            assert "Stellar Blade import ok: imported".equals(link.answer(2_000_000_000L));
            assert link.request("enter", now, 0) == null && link.answer(4_000_000_000L) == null
                    && link.answer(6_000_000_000L).contains("did not answer /stellar enter");

            link.stop();
            assert !Files.exists(directory.resolve("terrain.txt")) && !Files.exists(directory.resolve("eve-mobs.txt"));
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Bridge link tests passed: client clock on every file, relay once, skewed server judged by its sender's clock, per-player relays isolated");
    }

    private static long time(Path directory, String name) throws Exception {
        for (String line : Files.readString(directory.resolve(name)).split("\n"))
            if (line.startsWith("TIME ")) return Long.parseLong(line.substring(5));
        throw new AssertionError("No TIME in " + name);
    }
}

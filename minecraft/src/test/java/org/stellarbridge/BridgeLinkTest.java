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
            link.tick(now + 150);
            assert time(directory, "eve-mobs.txt") == now + 150 : "Walls renewed every tick: 4 ticks at a slowdown's rate outlast Lua's lease";
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

            // A teleport out of the window ends the server's session; walking out of it, or a jump inside it, does not.
            var patch = new net.minecraft.util.math.Box(80.5, 56, -40.5, 120.5, 80, -0.5);
            assert BridgeServer.teleported(new Vec3d(100, 64, -20), new Vec3d(300, 64, -20), patch) : "A /tp far away";
            assert !BridgeServer.teleported(new Vec3d(120, 64, -20), new Vec3d(121, 64, -20), patch) : "Walking out";
            assert !BridgeServer.teleported(new Vec3d(82, 64, -20), new Vec3d(118, 64, -20), patch) : "A long jump inside the window";
            assert !BridgeServer.teleported(null, new Vec3d(300, 64, -20), patch) : "Nothing to compare before the first tick";

            link.stop();
            assert !Files.exists(directory.resolve("terrain.txt")) && !Files.exists(directory.resolve("eve-mobs.txt"));

            // Windows refuses a replacement while Stellar Blade's Lua holds the target open (an alt-tab can deschedule its
            // game thread mid-read): a refused heartbeat is written again at the next tick, and only refusals lasting
            // BridgeLink.BUSY_MS end the link.
            Path flakyDirectory = temporary.resolve("flaky");
            var refused = new java.util.HashSet<String>();
            BridgeFiles flaky = new BridgeFiles(flakyDirectory, (from, to) -> {
                if (refused.contains(to.getFileName().toString())) throw new java.nio.file.AccessDeniedException(from + " -> " + to);
                Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            });
            BridgeLink busy = new BridgeLink(flaky, A, origin, "minecraft:overworld");
            long t = client + 10_000;
            refused.add("terrain.txt");
            busy.terrain(new BridgeProtocol.Terrain(A, 1, server / 1000, "minecraft:overworld", origin.x, origin.y, origin.z,
                    window, List.of(box)), t);
            assert busy.revision() == 1 && !Files.exists(flakyDirectory.resolve("terrain.txt")) : "A refused revision does not end the link";
            refused.clear();
            busy.tick(t + 50);
            assert time(flakyDirectory, "terrain.txt") == (t + 50) / 1000 : "Written at the next tick, without waiting for the heartbeat";
            Files.writeString(flakyDirectory.resolve("eve.0"), eve.replace("TIME " + now / 1000, "TIME " + (t + 100) / 1000));
            busy.tick(t + 100);
            busy.mobs(List.of(box), t + 150);
            assert time(flakyDirectory, "eve-mobs.txt") == t + 150;
            refused.add("eve-mobs.txt");
            for (long at = t + 200; at < t + 200 + BridgeLink.BUSY_MS; at += 50) busy.tick(at);
            assert time(flakyDirectory, "eve-mobs.txt") == t + 150 : "Refused walls keep the last ones, and the link lives";
            refused.clear();
            long back = t + 200 + BridgeLink.BUSY_MS;
            busy.tick(back);
            assert time(flakyDirectory, "eve-mobs.txt") == back : "The walls are renewed once Lua lets go";
            refused.add("eve-mobs.txt");
            long endedAt = -1;
            for (long at = back + 50; endedAt < 0 && at <= back + 50 + 2 * BridgeLink.BUSY_MS; at += 50) {
                try { busy.tick(at); }
                catch (java.nio.file.AccessDeniedException persistent) { endedAt = at; }
            }
            assert endedAt == back + 50 + BridgeLink.BUSY_MS : "Refusals lasting BUSY_MS end the link: " + endedAt;
            refused.clear();

            // A run of refusals that ends because nothing is due any more (the walls emptied before a retry got through)
            // is over: a refusal much later starts a new run with the whole BUSY_MS again.
            java.util.function.BiFunction<Integer, Long, String> packet = (seq, ms) -> "SBMC 2 EVE\nSESSION " + A + "\nSEQ " + seq
                    + "\nTIME " + ms / 1000 + "\nSTATE entered 1\nPOS 1.5 0 -2.25\nEND " + seq + "\n";
            BridgeLink later = new BridgeLink(flaky, A, origin, "minecraft:overworld");
            long t2 = t + 20_000;
            later.terrain(new BridgeProtocol.Terrain(A, 1, server / 1000, "minecraft:overworld", origin.x, origin.y, origin.z,
                    window, List.of(box)), t2);
            Files.writeString(flakyDirectory.resolve("eve.0"), packet.apply(7, t2));
            later.tick(t2 + 50);
            refused.add("eve-mobs.txt");
            later.mobs(List.of(box), t2 + 100);
            later.mobs(List.of(), t2 + 150);
            refused.clear();
            Files.writeString(flakyDirectory.resolve("eve.0"), packet.apply(8, t2 + 3100));
            later.tick(t2 + 3100);
            assert later.position() != null;
            refused.add("eve-mobs.txt");
            later.mobs(List.of(box), t2 + 3150);
            later.tick(t2 + 3200);
            refused.clear();
            later.tick(t2 + 3250);
            assert time(flakyDirectory, "eve-mobs.txt") == t2 + 3250 : "A later refusal got its own grace, then the walls went through";

            // Lua reads eve-combat.txt every frame too: a refused verdict is written again, as it was, at the next ticks;
            // one Windows keeps refusing past VERDICT_MS is dropped with a warning, never the link.
            BridgeLink fights = new BridgeLink(flaky, A, origin, "minecraft:overworld");
            long t3 = t + 30_000;
            refused.add("eve-combat.txt");
            fights.verdict(BridgeProtocol.COMBAT_EVENTS.indexOf("parry"), 90, t3);
            fights.tick(t3 + 50);
            assert !Files.exists(flakyDirectory.resolve("eve-combat.txt")) : "Still refused";
            refused.clear();
            fights.tick(t3 + 100);
            assert time(flakyDirectory, "eve-combat.txt") == t3
                    && Files.readString(flakyDirectory.resolve("eve-combat.txt")).contains("EVENT parry 90.0\n") : "Written once Lua lets go";
            refused.add("eve-combat.txt");
            fights.verdict(BridgeProtocol.COMBAT_EVENTS.indexOf("parry"), 45, t3 + 2000);
            for (long at = t3 + 2050; at <= t3 + 2000 + 2 * BridgeLink.VERDICT_MS; at += 50) fights.tick(at);
            refused.clear();
            fights.tick(t3 + 2000 + 2 * BridgeLink.VERDICT_MS + 50);
            assert time(flakyDirectory, "eve-combat.txt") == t3 : "Dropped past VERDICT_MS, and the link ticked through it";
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Bridge link tests passed: client clock on every file, relay once, skewed server judged by its sender's clock, per-player relays isolated, teleports out of the window, refused heartbeats written again until BUSY_MS, refused verdicts until VERDICT_MS");
    }

    private static long time(Path directory, String name) throws Exception {
        for (String line : Files.readString(directory.resolve(name)).split("\n"))
            if (line.startsWith("TIME ")) return Long.parseLong(line.substring(5));
        throw new AssertionError("No TIME in " + name);
    }
}

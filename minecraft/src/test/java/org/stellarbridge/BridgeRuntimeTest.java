package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

public final class BridgeRuntimeTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        PoseGate gate = new PoseGate(SESSION);
        assert gate.away(100) : "No packet yet: away";
        gate.published(1, new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5));
        var entered = new BridgeProtocol.Eve(SESSION, 4, 100, "entered", 1, 1, 0, 2);
        assert gate.accept(entered, 1, 100) == entered && !gate.away(100);
        assert gate.accept(entered, 1, 105) == entered;
        assert gate.accept(entered, 1, 106) == null && gate.away(106) : "Expired pose must release movement";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 3, 105, "entered", 1, 1, 0, 2), 1, 105) == null : "Reject sequence rollback";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 4, 105, "entered", 1, 2, 0, 2), 1, 105) == null : "A sequence cannot mutate";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 5, 105, "idle", 1, 1, 0, 2), 1, 105) == null && gate.away(105)
                : "Returned: away";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 6, 105, "entered", 2, 1, 0, 2), 1, 105) == null : "Reject unpublished terrain acknowledgment";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 7, 105, "entered", 1, 5, 0, 2), 1, 105) == null : "Reject position outside sampled patch";
        assert gate.accept(new BridgeProtocol.Eve("ffffffffffffffffffffffffffffffff", 8, 105, "entered", 1, 1, 0, 2), 1, 105) == null;
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 9, 105, "entered", 1, 1, 0, 2), 1, 105) != null;
        assert gate.accept(null, 1, 105) == null;
        // The window moved with revision 2: a position is judged by the window of the revision EVE acknowledges, whose
        // guards hold her in Stellar Blade. Edits that keep a window never forget it, however many there are.
        var moved = new BridgeProtocol.Box(11.5, -2, -4.5, 20.5, 4, 4.5);
        gate.published(2, moved);
        for (int revision = 3; revision <= 100; revision++) gate.published(revision, moved);
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 10, 105, "entered", 1, 3, 0, 2), 100, 105) != null
                : "Revision 1's window while EVE still acknowledges it, 99 revisions later";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 11, 105, "entered", 1, 13, 0, 2), 100, 105) == null
                : "Not yet the moved window before she acknowledges it";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 12, 105, "entered", 50, 13, 0, 2), 100, 105) != null
                : "An edit of the moved window holds it";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 13, 105, "entered", 2, 3, 0, 2), 100, 105) == null
                : "Nor the old window once she acknowledges the moved one";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 14, 105, "entered", 1, 3, 0, 2), 100, 105) == null
                : "An acknowledged move forgets the older window";

        Path temporary = Files.createTempDirectory("stellar-bridge-test-");
        try {
            Path directory = temporary.resolve("bridge");
            BridgeFiles files = new BridgeFiles(directory);
            assert Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"));
            files.publish("first\n");
            files.publish("second\n");
            assert Files.readString(directory.resolve("terrain.txt")).equals("second\n");
            assert files.readEve(SESSION) == null;
            String packet = "SBMC 2 EVE\nSESSION " + SESSION + "\nSEQ 3\nTIME 100\nSTATE entered 1\nPOS 0 0 0\nEND 3\n";
            Files.writeString(directory.resolve("eve.0"), packet);
            Files.writeString(directory.resolve("eve.1"), packet.replace("SEQ 3", "SEQ 4").replace("END 3", "END 4"));
            assert files.readEve(SESSION).seq() == 4;
            Files.writeString(directory.resolve("eve.1"), "partial");
            assert files.readEve(SESSION).seq() == 3;
            Files.writeString(directory.resolve("eve.1"), "x".repeat(65537));
            assert files.readEve(SESSION).seq() == 3;
            Files.delete(directory.resolve("eve.1")); // Stellar Blade's writer between removing a slot and renaming its replacement.
            assert files.readEve(SESSION).seq() == 3;
            Files.createDirectory(directory.resolve("eve.1"));
            try { files.readEve(SESSION); throw new AssertionError("Non-regular slot accepted"); }
            catch (java.io.IOException expected) { }
            Files.delete(directory.resolve("eve.1"));
            for (String name : new String[] {"eve-combat.txt", "eve-lock.txt", "eve-mobs.txt", "eve-request.txt", "eve-request-ack.txt"}) files.write(name, "x\n");
            files.stop();
            assert !Files.exists(directory.resolve("terrain.txt"));
            for (String name : new String[] {"eve-combat.txt", "eve-lock.txt", "eve-mobs.txt", "eve-request.txt", "eve-request-ack.txt"})
                assert !Files.exists(directory.resolve(name)) : name + " outlives the bridge";
            Path outside = temporary.resolve("outside.txt");
            Files.writeString(outside, "untouched");
            Files.createSymbolicLink(directory.resolve("terrain.txt"), outside);
            try { files.publish("unsafe"); throw new AssertionError("Symlink accepted"); }
            catch (java.io.IOException expected) { }
            assert Files.readString(outside).equals("untouched");
            Files.createSymbolicLink(temporary.resolve("linked"), directory);
            try { new BridgeFiles(temporary.resolve("linked")); throw new AssertionError("Directory symlink accepted"); }
            catch (java.io.IOException expected) { }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Bridge runtime tests passed");
    }
}

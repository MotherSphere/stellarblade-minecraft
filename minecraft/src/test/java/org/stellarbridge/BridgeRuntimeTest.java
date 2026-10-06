package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;

public final class BridgeRuntimeTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        PoseGate gate = new PoseGate(SESSION, new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5));
        var entered = new BridgeProtocol.Eve(SESSION, 4, 100, "entered", 1, 1, 0, 2);
        assert gate.accept(entered, 1, 100) == entered;
        assert gate.accept(entered, 1, 105) == entered;
        assert gate.accept(entered, 1, 106) == null : "Expired pose must release movement";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 3, 105, "entered", 1, 1, 0, 2), 1, 105) == null : "Reject sequence rollback";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 4, 105, "entered", 1, 2, 0, 2), 1, 105) == null : "A sequence cannot mutate";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 5, 105, "idle", 1, 1, 0, 2), 1, 105) == null;
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 6, 105, "entered", 2, 1, 0, 2), 1, 105) == null : "Reject unpublished terrain acknowledgment";
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 7, 105, "entered", 1, 5, 0, 2), 1, 105) == null : "Reject position outside sampled patch";
        assert gate.accept(new BridgeProtocol.Eve("ffffffffffffffffffffffffffffffff", 8, 105, "entered", 1, 1, 0, 2), 1, 105) == null;
        assert gate.accept(new BridgeProtocol.Eve(SESSION, 9, 105, "entered", 1, 1, 0, 2), 1, 105) != null;
        assert gate.accept(null, 1, 105) == null;

        Path temporary = Files.createTempDirectory("stellar-bridge-test-");
        try {
            Path directory = temporary.resolve("bridge");
            BridgeFiles files = new BridgeFiles(directory);
            assert Files.getPosixFilePermissions(directory).equals(PosixFilePermissions.fromString("rwx------"));
            files.publish("first\n");
            files.publish("second\n");
            assert Files.readString(directory.resolve("terrain.txt")).equals("second\n");
            assert files.readEve(SESSION) == null;
            String packet = "SBMC 1 EVE\nSESSION " + SESSION + "\nSEQ 3\nTIME 100\nSTATE entered 1\nPOS 0 0 0\nEND 3\n";
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
            files.stop();
            assert !Files.exists(directory.resolve("terrain.txt"));
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

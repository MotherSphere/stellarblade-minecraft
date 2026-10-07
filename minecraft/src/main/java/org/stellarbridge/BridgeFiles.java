package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

/** Small local snapshots only; neither packet contents nor paths are executable. */
final class BridgeFiles {
    private static final int MAX_BYTES = 65536;
    private final Path directory;

    BridgeFiles(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Path current = this.directory.getRoot();
        for (Path part : this.directory) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Bridge directory must not contain symlinks or non-directories");
        }
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
    }

    void publish(String packet) throws IOException { write("terrain.txt", packet); }

    /** Atomic replacement of one bounded private snapshot. */
    void write(String filename, String packet) throws IOException {
        byte[] data = packet.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IOException(filename + " exceeds 64 KiB");
        Path target = checked(filename);
        Path temporary = Files.createTempFile(directory, "snapshot-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.write(temporary, data);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    BridgeProtocol.Eve readEve(String session) throws IOException {
        BridgeProtocol.Eve newest = null;
        for (int slot = 0; slot < 2; slot++) {
            Path path = checked("eve." + slot);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) continue;
                BridgeProtocol.Eve packet = BridgeProtocol.parseEve(new String(bytes, StandardCharsets.UTF_8));
                if (packet.session().equals(session) && (newest == null || packet.seq() > newest.seq())) newest = packet;
            } catch (IllegalArgumentException | java.nio.file.NoSuchFileException rejected) {
                // A missing inactive slot or malformed publication never controls the player.
            }
        }
        return newest;
    }

    /** A bounded snapshot, or null when it is missing, oversized or not a regular file. */
    String read(String filename) throws IOException {
        Path path = checked(filename);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            return bytes.length > MAX_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException replaced) {
            return null;
        }
    }

    void stop() throws IOException {
        Files.deleteIfExists(checked("terrain.txt"));
        Files.deleteIfExists(checked("eve-combat.txt"));
        Files.deleteIfExists(checked("eve-lock.txt"));
        Files.deleteIfExists(checked("eve-mobs.txt"));
    }

    Path checked(String filename) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Bridge directory changed or became a symlink");
        }
        Path path = directory.resolve(filename);
        // One stat: Stellar Blade's writer removes a slot before renaming its replacement (MSVC rename refuses an existing
        // destination), so a file seen to exist may be gone a moment later. Missing is fine; anything else must be a
        // regular file, never a symlink.
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
                throw new IOException("Bridge snapshot must be a regular file");
        } catch (NoSuchFileException missing) {
            // Absent or between a removal and a rename: callers treat it as absent.
        }
        return path;
    }
}

package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    void publish(String packet) throws IOException {
        byte[] data = packet.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IOException("Terrain snapshot exceeds 64 KiB");
        Path target = checked("terrain.txt");
        Path temporary = Files.createTempFile(directory, "terrain-", ".tmp",
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

    void stop() throws IOException { Files.deleteIfExists(checked("terrain.txt")); }

    Path checked(String filename) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Bridge directory changed or became a symlink");
        }
        Path path = directory.resolve(filename);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
            throw new IOException("Bridge snapshot must be a regular file");
        return path;
    }
}

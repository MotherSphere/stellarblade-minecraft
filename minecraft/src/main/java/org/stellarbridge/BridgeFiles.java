package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Small local snapshots only; neither packet contents nor paths are executable. Owner-only: modes 0700 and 0600 where the
 * file system has POSIX permissions (Linux), the user profile's inherited ACL on Windows.
 */
final class BridgeFiles {
    private static final int MAX_BYTES = 65536;
    static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    // A refused replace is tried again this many times, 1 ms apart (see write).
    private static final int REPLACE_RETRIES = 5;
    private final Path directory;

    BridgeFiles(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Path current = this.directory.getRoot();
        for (Path part : this.directory) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                Files.createDirectory(current, owner("rwx------"));
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Bridge directory must not contain symlinks or non-directories");
        }
        if (POSIX) Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
    }

    /** These owner-only POSIX permissions where the file system has them; none on Windows, whose profile ACL applies. */
    static FileAttribute<?>[] owner(String permissions) {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))}
                : new FileAttribute<?>[0];
    }

    void publish(String packet) throws IOException { write("terrain.txt", packet); }

    /** Atomic replacement of one bounded private snapshot. */
    void write(String filename, String packet) throws IOException {
        byte[] data = packet.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IOException(filename + " exceeds 64 KiB");
        Path target = checked(filename);
        Path temporary = Files.createTempFile(directory, "snapshot-", ".tmp", owner("rw-------"));
        try {
            Files.write(temporary, data);
            // Windows refuses to replace a file another process holds open without delete sharing, as Lua's io.open and
            // the adapter's readers do for microseconds. Linux rename never refuses it here.
            // ponytail: bounded retry; a reader holding a file >5 ms still fails and is reported as today.
            for (int retry = 0; ; retry++) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    break;
                } catch (AccessDeniedException busy) {
                    if (retry == REPLACE_RETRIES) throw busy;
                    try { Thread.sleep(1); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw busy;
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The newest valid eve.N packet of the session, as Lua wrote it (the client relays this text), or null. */
    String readEve(String session) throws IOException {
        BridgeProtocol.Eve newest = null;
        String text = null;
        for (int slot = 0; slot < 2; slot++) {
            Path path = checked("eve." + slot);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) continue;
                String read = new String(bytes, StandardCharsets.UTF_8);
                BridgeProtocol.Eve packet = BridgeProtocol.parseEve(read);
                if (packet.session().equals(session) && (newest == null || packet.seq() > newest.seq())) {
                    newest = packet;
                    text = read;
                }
            } catch (IllegalArgumentException | NoSuchFileException | AccessDeniedException rejected) {
                // A missing inactive slot or malformed publication never controls the player. Windows denies access to a
                // slot Lua has removed but not yet replaced (a pending removal): absent this time.
            }
        }
        return text;
    }

    /** A bounded snapshot, or null when it is missing, oversized or not a regular file. */
    String read(String filename) throws IOException {
        Path path = checked(filename);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            return bytes.length > MAX_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException | AccessDeniedException replaced) {
            return null; // Replaced since the check; on Windows also a removal still pending.
        }
    }

    void stop() throws IOException {
        Files.deleteIfExists(checked("terrain.txt"));
        Files.deleteIfExists(checked("eve-combat.txt"));
        Files.deleteIfExists(checked("eve-lock.txt"));
        Files.deleteIfExists(checked("eve-mobs.txt"));
        Files.deleteIfExists(checked("eve-request.txt"));
        Files.deleteIfExists(checked("eve-request-ack.txt"));
        // The adapter's eve-wardrobe.txt stays: it is the last owned list, which the pack install reads.
        Files.deleteIfExists(checked("eve-wardrobe-request.txt"));
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
        } catch (NoSuchFileException | AccessDeniedException missing) {
            // Absent or between a removal and a rename (on Windows a pending removal denies access): callers treat it as
            // absent.
        }
        return path;
    }
}

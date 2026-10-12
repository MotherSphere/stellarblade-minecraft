package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
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
    // A refused replace is tried again this many times, 1 ms apart (see replace).
    private static final int REPLACE_RETRIES = 5;
    private final Path directory;
    private final Mover mover;

    /** Moves a written snapshot over its target; a test's refuses as Windows does while a reader holds the target open. */
    interface Mover { void move(Path temporary, Path target) throws IOException; }

    interface Write { void run() throws IOException; }

    /**
     * One heartbeat file's run of refused replacements. Stellar Blade's Lua reads with io.open, which shares no deletion,
     * and its game thread can hold a file past replace's retries while Windows runs another process (an alt-tab is
     * enough): a heartbeat written again at its next due time survives such refusals until they have lasted limit
     * milliseconds. Refusals more than GAP_MS apart are separate runs, as when the file stopped being due in between.
     */
    static final class Refusals {
        private static final long GAP_MS = 500;
        private final long limit;
        private long since = -1, last;

        Refusals(long limit) { this.limit = limit; }

        /** Writes; false while Windows has refused the replacement for less than limit, else throws. */
        boolean write(Write write, long now) throws IOException {
            try {
                write.run();
                since = -1;
                return true;
            } catch (FileSystemException refused) {
                // A reader holding the target (access denied) or the source shared badly (sharing violation); anything
                // more specific (missing, a link, a file in the way) is no transient refusal.
                if (!(refused instanceof AccessDeniedException) && refused.getClass() != FileSystemException.class) throw refused;
                if (since < 0 || now - last > GAP_MS || now < last) since = now;
                last = now;
                if (now - since >= limit) throw refused;
                return false;
            }
        }
    }

    BridgeFiles(Path directory) throws IOException { this(directory, BridgeFiles::move); }

    BridgeFiles(Path directory, Mover mover) throws IOException {
        this.mover = mover;
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
            replace(temporary, target, mover);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Atomically moves {@code temporary} over {@code target}, as every snapshot a reader polls is replaced. */
    static void replace(Path temporary, Path target) throws IOException { replace(temporary, target, BridgeFiles::move); }

    private static void move(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void replace(Path temporary, Path target, Mover mover) throws IOException {
        // Windows refuses to replace a file while a reader holds it open (MoveFileEx has no POSIX rename semantics), as
        // Lua's io.open and the adapter's readers do for microseconds. Linux rename never refuses it here.
        // A reader holding it past these retries (Stellar Blade's game thread descheduled mid-read) still fails here;
        // BridgeLink writes such a heartbeat again at the next tick.
        for (int retry = 0; ; retry++) {
            try {
                mover.move(temporary, target);
                return;
            } catch (AccessDeniedException busy) {
                if (retry == REPLACE_RETRIES) throw busy;
                try { Thread.sleep(1); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
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

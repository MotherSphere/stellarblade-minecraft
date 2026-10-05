package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Atomic local input publication and bounded native acknowledgement reads. */
final class ControlFiles {
    private final BridgeFiles files;
    ControlFiles(Path directory) throws IOException { files = new BridgeFiles(directory); }
    void publish(String packet) throws IOException {
        if (packet.length() > 4096) throw new IOException("Controller packet exceeds limits");
        Path target = files.checked("eve-input.txt");
        Path temporary = Files.createTempFile(target.getParent(), "control-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, packet, StandardCharsets.US_ASCII);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    ControlState.Ack readAck() throws IOException {
        try (var input = Files.newInputStream(files.checked("eve-input-ack.txt"), LinkOption.NOFOLLOW_LINKS)) {
            return ControlState.parseAck(input.readNBytes(513));
        }
    }
}

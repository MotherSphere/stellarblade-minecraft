package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Atomic local input publication and bounded native acknowledgement reads. */
final class ControlFiles {
    private final BridgeFiles files;
    ControlFiles(Path directory) throws IOException { files = new BridgeFiles(directory); }
    void publish(String packet) throws IOException {
        if (packet.length() > 4096) throw new IOException("Controller packet exceeds limits");
        files.write("eve-input.txt", packet);
    }
    ControlState.Ack readAck() throws IOException {
        try (var input = Files.newInputStream(files.checked("eve-input-ack.txt"), LinkOption.NOFOLLOW_LINKS)) {
            return ControlState.parseAck(input.readNBytes(513));
        }
    }
}

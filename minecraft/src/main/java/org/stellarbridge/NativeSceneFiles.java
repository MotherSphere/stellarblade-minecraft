package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Worker-owned publication: one active generation and one bounded replacement. */
final class NativeSceneFiles {
    private final BridgeFiles paths;
    private final Set<String> owned = new HashSet<>(), retired = new HashSet<>();
    private Set<String> active = Set.of();
    private final Map<NativeScene.Texture, Cached> cache = new HashMap<>();
    private NativeScene.Binding binding;
    private String content;
    private int revision;
    private boolean descriptorOwned;
    private boolean stopping;
    private record Cached(byte[] base, byte[] overlay, NativeScene.Tile tile) { }

    NativeSceneFiles(Path directory) throws IOException { paths = new BridgeFiles(directory); }

    Publication stage(NativeScene.Binding nextBinding, List<NativeScene.Face> faces, Map<String, byte[]> resources, long now) throws IOException {
        if (stopping) stop();
        cleanup(); // Failed deletion obligations block further allocations.
        List<NativeScene.Tile> tiles = new ArrayList<>();
        Set<NativeScene.Texture> keys = new LinkedHashSet<>();
        for (var face : faces) keys.add(face.texture());
        if (keys.size() > 32) throw new IOException("Native scene exceeds 32 textures");
        cache.keySet().retainAll(keys);
        Set<String> nextFiles = new HashSet<>();
        String temporary = "mc-scene-" + nextBinding.session() + ".tmp";
        try {
            for (var key : keys) {
                byte[] base = resources.get(key.base()), overlay = key.overlay() == null ? null : resources.get(key.overlay());
                if (base == null || key.overlay() != null && overlay == null) throw new IOException("Missing scene texture resource");
                Cached cached = cache.get(key);
                if (cached == null || !Arrays.equals(base, cached.base) || !Arrays.equals(overlay, cached.overlay)) {
                    byte[] png = NativeScene.tile(base, overlay, key.tint());
                    cached = new Cached(base, overlay, new NativeScene.Tile(key, NativeScene.digest(png), png));
                    cache.put(key, cached);
                }
                tiles.add(cached.tile);
                String name = NativeScene.filename(nextBinding, cached.tile.digest());
                nextFiles.add(name);
                if (!owned.contains(name)) writeOwned(name, cached.tile.png());
            }
            String body = NativeScene.body(nextBinding, tiles, faces);
            int nextRevision = binding == null || !binding.session().equals(nextBinding.session()) ? 1
                    : binding.equals(nextBinding) && body.equals(content) ? revision : Math.addExact(revision, 1);
            writeOwned(temporary, NativeScene.encode(nextBinding, nextRevision, now, tiles, faces).getBytes(StandardCharsets.US_ASCII));
            return new Publication(nextBinding, body, nextRevision, nextFiles, temporary);
        } catch (IOException | RuntimeException failure) {
            for (String name : owned) if (!active.contains(name)) retired.add(name);
            try { cleanup(); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private void writeOwned(String name, byte[] bytes) throws IOException {
        Path path = paths.checked(name);
        if (!owned.contains(name) && Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Refusing to replace an unowned scene generation file: " + name);
        owned.add(name); // Keep partial writes as cleanup obligations.
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    final class Publication implements AutoCloseable {
        private final NativeScene.Binding nextBinding;
        private final String body, temporary;
        private final int nextRevision;
        private final Set<String> nextFiles;
        private boolean committed;

        Publication(NativeScene.Binding nextBinding, String body, int nextRevision, Set<String> nextFiles, String temporary) {
            this.nextBinding = nextBinding;
            this.body = body;
            this.nextRevision = nextRevision;
            this.nextFiles = nextFiles;
            this.temporary = temporary;
        }

        void commit() throws IOException {
            // Lua polls the descriptor with io.open, which Windows lets refuse the replace for a moment.
            BridgeFiles.replace(paths.checked(temporary), paths.checked("native-scene.txt"));
            owned.remove(temporary);
            descriptorOwned = true;
            for (String name : active) if (!nextFiles.contains(name)) retired.add(name);
            active = Set.copyOf(nextFiles);
            binding = nextBinding;
            content = body;
            revision = nextRevision;
            committed = true;
        }

        @Override public void close() throws IOException {
            if (!committed) {
                retired.add(temporary);
                for (String name : nextFiles) if (!active.contains(name)) retired.add(name);
            }
            cleanup();
        }
    }

    void stop() throws IOException {
        stopping = true;
        if (descriptorOwned) {
            Files.deleteIfExists(paths.checked("native-scene.txt"));
            descriptorOwned = false;
        }
        retired.addAll(owned);
        cleanup();
        active = Set.of();
        cache.clear();
        // A resource reload or temporarily unsupported cell may resume the same live session.
        // Keep its revision history; a genuinely new session starts at one in stage().
        stopping = false;
    }

    private void cleanup() throws IOException {
        IOException problem = null;
        for (String name : List.copyOf(retired)) {
            try {
                Files.deleteIfExists(paths.checked(name));
                retired.remove(name);
                owned.remove(name);
            } catch (IOException failure) { problem = failure; }
        }
        if (problem != null) throw new IOException("Native scene file cleanup is pending; publication paused", problem);
    }
}

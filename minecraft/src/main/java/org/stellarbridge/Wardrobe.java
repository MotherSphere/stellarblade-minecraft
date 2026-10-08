package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * EVE's wardrobe apart from Minecraft's GUI: the slots WardrobeScreen offers, the private pack's item catalog
 * (wardrobe.txt), the screen's layout and the pack's manifest of the game files it was built from (pack.txt).
 */
final class Wardrobe {
    private Wardrobe() {}

    /**
     * A slot as Stellar Blade's equipment menu groups them: its ESBEquipType, the ESBItemCategory that fits it, its group,
     * its lang key and the pack icon drawn while it is empty.
     */
    record Slot(int type, int category, String group, String key, String empty) {}
    static final List<Slot> SLOTS = List.of(
            new Slot(6, 3, "exospine", "exospine", "ExospineNormal"), new Slot(7, 3, "exospine", "exospine", "ExospineNormal"),
            new Slot(1, 2, "gear", "gear", "CommonGearDim"), new Slot(2, 2, "gear", "gear", "CommonGearDim"),
            new Slot(3, 2, "gear", "gear", "CommonGearDim"), new Slot(4, 2, "gear", "gear", "CommonGearDim"),
            new Slot(5, 4, "look", "suit", "Lookfocus_Icon00"), new Slot(11, 22, "look", "glasses", "Lookfocus_Icon01"),
            new Slot(10, 23, "look", "earrings", "Lookfocus_Icon02"), new Slot(17, 28, "look", "drone", "Lookfocus_Icon03"),
            new Slot(8, 21, "look", "hair", "Lookfocus_Icon06"));
    static final List<String> GROUPS = List.of("exospine", "gear", "look");
    static final int MAX_BONES = 256; // The pose stream's limit per mesh (Lua's pose table, AvatarStream).

    /**
     * The adapter's v1 slot rule: equip into a slot that holds an item, or into the earring (10) or face accessory (11)
     * slot; only those two may be emptied. The suit and the hair are never emptied, and empty gear or exospine slots,
     * which may be locked, are not offered.
     */
    static boolean equippable(int slot, boolean occupied) { return occupied || removable(slot); }
    static boolean removable(int slot) { return slot == 10 || slot == 11; }

    /** One catalog row: ID, category, alias, rarity, icon (null: none), largest bone count, meshes, name, description. */
    record Item(long id, int category, String alias, int rarity, String icon, int maxBones, List<String> meshes, String name, String description) {}
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");

    /**
     * The pack's wardrobe.txt (SBAVWARDROBE 1, UTF-8): one tab-separated item per line (ID, category, alias, rarity,
     * icon or -, the largest bone count of its meshes, comma-separated meshes or -, name, description), then END and
     * the item count. Null for another version or any malformed line: the screen then names items by ID.
     */
    static Map<Long, Item> catalog(String text) {
        if (text == null || !text.startsWith("SBAVWARDROBE 1\n") || !text.endsWith("\n")) return null;
        String[] lines = text.split("\n", -1);
        Map<Long, Item> items = new HashMap<>();
        for (int k = 1; k < lines.length - 2; k++) {
            String[] f = lines[k].split("\t", -1);
            if (f.length != 9 || !NUMBER.matcher(f[0]).matches() || !NUMBER.matcher(f[1]).matches() || !NUMBER.matcher(f[3]).matches()
                    || !NUMBER.matcher(f[5]).matches() || f[2].isEmpty() || f[4].isEmpty() || f[6].isEmpty() || f[7].isEmpty()) return null;
            Item item = new Item(Long.parseLong(f[0]), Integer.parseInt(f[1]), f[2], Integer.parseInt(f[3]), f[4].equals("-") ? null : f[4],
                    Integer.parseInt(f[5]), f[6].equals("-") ? List.of() : List.of(f[6].split(",", -1)), f[7], f[8]);
            if (item.meshes.contains("") || items.put(item.id, item) != null) return null;
        }
        return lines.length >= 3 && lines[lines.length - 2].equals("END " + items.size()) ? items : null;
    }

    /** The wardrobe.txt beside the avatar's meshes, or null when absent or unreadable (logged). */
    static Map<Long, Item> catalog(Path pack) {
        Path file = pack.resolve("wardrobe.txt");
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<Long, Item> items = catalog(Files.readString(file, StandardCharsets.UTF_8));
            if (items == null) org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("Avatar pack wardrobe.txt unreadable; items are named by ID");
            return items;
        } catch (IOException | RuntimeException unreadable) {
            org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("Avatar pack wardrobe.txt unreadable: {}", unreadable.toString());
            return null;
        }
    }

    /**
     * WardrobeScreen's layout in GUI pixels: margin MARGIN, a HEADER (title, the slot's name) and a FOOTER (key hints);
     * rows of 32 px from a height of 400, else 22, slot cells 2 px larger. Left the slot column (two cells a row under
     * 10-px group headings: Exospine 1 row, Gear 2, Look 3), then the item list, a third of the width within 140 to 220,
     * then EVE's preview up to the right margin, with CAPTION px of two text lines under it. ponytail: two fixed row
     * sizes, not Stellar Blade's continuous scale; scale like the HUD's panel if 960 x 540 looks too sparse.
     */
    record Layout(int row, int cell, int slotsX, int listX, int listWidth, int top, int bottom, int previewX0, int previewX1, int previewY1) {
        /** The top-left corner of SLOTS' slot i. */
        int[] slot(int i) {
            String group = SLOTS.get(i).group;
            int first = (int) SLOTS.stream().takeWhile(s -> !s.group.equals(group)).count(), k = i - first;
            return new int[] {slotsX + k % 2 * cell, heading(group) + 10 + k / 2 * cell};
        }
        /** The top of a group's heading. */
        int heading(String group) {
            int y = top;
            for (String g : GROUPS.subList(0, GROUPS.indexOf(group)))
                y += 10 + ((int) SLOTS.stream().filter(s -> s.group.equals(g)).count() + 1) / 2 * cell;
            return y;
        }
    }
    static final int MARGIN = 8, HEADER = 20, FOOTER = 14, CAPTION = 20;

    static Layout layout(int width, int height) {
        int row = height >= 400 ? 32 : 22, cell = row + 2, listX = MARGIN + 2 * cell + MARGIN, list = Math.clamp(width / 3, 140, 220);
        return new Layout(row, cell, MARGIN, listX, list, HEADER, height - FOOTER, listX + list + MARGIN, width - MARGIN, height - FOOTER - CAPTION);
    }

    /** The pack's pack.txt (SBAVPACK 1): the Paks folder it was built from and each game file's "bytes mtime-ns" by path under it. */
    record Manifest(Path paks, Map<String, String> sources) {}
    private static final Pattern SOURCE = Pattern.compile("SOURCE (.+) ([0-9]{1,19}) ([0-9]{1,19})");

    /** Null unless the exact grammar holds: SBAVPACK 1, PAKS, then SOURCE and MESH lines, END. */
    static Manifest manifest(String text) {
        if (text == null || !text.startsWith("SBAVPACK 1\nPAKS ") || !text.endsWith("\nEND\n")) return null;
        String[] lines = text.split("\n", -1);
        Map<String, String> sources = new TreeMap<>();
        for (int k = 2; k < lines.length - 2; k++) {
            var m = SOURCE.matcher(lines[k]);
            if (m.matches()) {
                if (sources.put(m.group(1), m.group(2) + " " + m.group(3)) != null) return null;
            } else if (!lines[k].startsWith("MESH ")) return null;
        }
        String paks = lines[1].substring(5);
        return paks.isEmpty() ? null : new Manifest(Path.of(paks), sources);
    }

    /** What changed from the manifest's game files to those found: +added, -removed, ~changed (size or time), by path. */
    static List<String> differences(Map<String, String> built, Map<String, String> found) {
        List<String> out = new ArrayList<>();
        for (String path : new TreeSet<>(Stream.concat(built.keySet().stream(), found.keySet().stream()).toList())) {
            String was = built.get(path), now = found.get(path);
            if (was == null) out.add("+" + path);
            else if (now == null) out.add("-" + path);
            else if (!was.equals(now)) out.add("~" + path);
        }
        return out;
    }

    /** The game files under a Paks folder as pack.py lists them: .pak, .utoc and .ucas files by path under it, "bytes mtime-ns". */
    static Map<String, String> sources(Path paks) throws IOException {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(paks)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(file) || !(name.endsWith(".pak") || name.endsWith(".utoc") || name.endsWith(".ucas"))) continue;
                found.put(paks.relativize(file).toString().replace('\\', '/'),
                        Files.size(file) + " " + Files.getLastModifiedTime(file).to(TimeUnit.NANOSECONDS));
            }
        }
        return found;
    }

    /**
     * Whether the pack was built from Stellar Blade's game files as they are now (pack.txt against its Paks folder): a
     * one-line warning naming the first differences, or null when they match, the pack has no manifest (built before
     * mod tracking) or the Paks folder is not there to compare. It reads only file sizes and times; adding, removing or
     * updating a mod or a game patch changes them.
     */
    static String stalePack(Path pack) {
        Path file = pack.resolve("pack.txt");
        if (!Files.isRegularFile(file)) return null;
        try {
            Manifest manifest = manifest(Files.readString(file, StandardCharsets.UTF_8));
            if (manifest == null) return "Avatar pack manifest pack.txt is unreadable: rebuild the pack with both games closed.";
            if (!Files.isDirectory(manifest.paks)) return null;
            List<String> changed = differences(manifest.sources, sources(manifest.paks));
            if (changed.isEmpty()) return null;
            return "Avatar pack built for other Stellar Blade game files (" + String.join(", ", changed.subList(0, Math.min(5, changed.size())))
                    + (changed.size() > 5 ? " and " + (changed.size() - 5) + " more" : "") + "): rebuild the pack with both games closed. EVE is still drawn.";
        } catch (IOException | RuntimeException unreadable) {
            return "Avatar pack manifest unreadable (" + unreadable + "): rebuild the pack with both games closed.";
        }
    }
}

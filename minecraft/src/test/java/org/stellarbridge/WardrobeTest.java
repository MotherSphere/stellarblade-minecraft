package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class WardrobeTest {
    public static void main(String[] args) throws Exception {
        // The pack's catalog, as pack.py writes it.
        String catalog = "SBAVWARDROBE 1\n"
                + "2000068\t4\tBS_08\t3\tNanoSuit_Icon_08\t230\tCH_P_EVE_08,CH_P_EVE_08_NH\tCombinaison d'été\tUne <Bold>description</>\n"
                + "103010\t2\tGear_BetaGaugeAdditiveRate_Common\t1\t-\t0\t-\tEngrenage\t\n"
                + "END 2\n";
        Map<Long, Wardrobe.Item> items = Wardrobe.catalog(catalog);
        assert items != null && items.size() == 2 : "The catalog parses";
        assert items.get(2000068L).equals(new Wardrobe.Item(2000068, 4, "BS_08", 3, "NanoSuit_Icon_08", 230,
                List.of("CH_P_EVE_08", "CH_P_EVE_08_NH"), "Combinaison d'été", "Une <Bold>description</>")) : items.get(2000068L);
        assert items.get(103010L).icon() == null && items.get(103010L).meshes().isEmpty() && items.get(103010L).description().isEmpty() : "- is none";
        assert Wardrobe.catalog("SBAVWARDROBE 1\nEND 0\n").isEmpty() : "An empty catalog";
        for (String bad : new String[] {catalog.replace("SBAVWARDROBE 1", "SBAVWARDROBE 2"), catalog.replace("END 2", "END 3"),
                catalog.replace("\tUne <Bold>description</>", ""), catalog.replace("2000068\t4", "x2000068\t4"), catalog.replace("\tCombinaison d'été", "\t"),
                catalog.replace("CH_P_EVE_08,CH_P_EVE_08_NH", "CH_P_EVE_08,"), catalog.replace("103010", "2000068"), catalog.replace("\t230\t", "\t-1\t"),
                catalog.substring(0, catalog.length() - 1), catalog.replace("END 2\n", ""), null})
            assert Wardrobe.catalog(bad) == null : "Refused: " + bad;

        // The adapter's slot rule, mirrored so the screen offers only what it would take.
        assert Wardrobe.equippable(5, true) && !Wardrobe.equippable(1, false) && !Wardrobe.equippable(6, false) : "Occupied slots only";
        assert Wardrobe.equippable(10, false) && Wardrobe.equippable(11, false) && Wardrobe.removable(10) && !Wardrobe.removable(5) && !Wardrobe.removable(8)
                && !Wardrobe.removable(17) && !Wardrobe.removable(1) && !Wardrobe.removable(6) : "Only earrings and glasses come off";
        assert Wardrobe.SLOTS.stream().mapToInt(Wardrobe.Slot::type).sorted().boxed().toList().equals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 17));

        // What each slot lists: every category the answer may carry has a slot, and with two items of each slot's category,
        // one worn there, in any order, each slot lists its category's items, the one worn there first, then by ID.
        assert Wardrobe.SLOTS.stream().map(Wardrobe.Slot::category).collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.copyOf(BridgeProtocol.WARDROBE_CATEGORIES)) : "A slot for each wardrobe category";
        List<BridgeProtocol.WardrobeItem> owned = new java.util.ArrayList<>();
        for (Wardrobe.Slot slot : Wardrobe.SLOTS) {
            owned.add(new BridgeProtocol.WardrobeItem(1000 + slot.type(), slot.category(), "%032x".formatted(slot.type()), 1, 0, slot.type()));
            owned.add(new BridgeProtocol.WardrobeItem(slot.type(), slot.category(), "%032x".formatted(100 + slot.type()), 1, 0, 0));
        }
        java.util.Collections.shuffle(owned, new java.util.Random(1));
        for (Wardrobe.Slot slot : Wardrobe.SLOTS) {
            List<BridgeProtocol.WardrobeItem> rows = Wardrobe.entries(owned, slot);
            assert rows.size() == 2 * Wardrobe.SLOTS.stream().filter(s -> s.category() == slot.category()).count()
                    && rows.stream().allMatch(i -> i.category() == slot.category()) && rows.get(0).slot() == slot.type()
                    && rows.subList(1, rows.size()).stream().mapToLong(BridgeProtocol.WardrobeItem::id).sorted().boxed().toList()
                    .equals(rows.subList(1, rows.size()).stream().map(BridgeProtocol.WardrobeItem::id).toList()) : slot + ": " + rows;
        }

        // The layout over the GUI sizes of 1080p at scale 2 and 3, a 1274 x 692 window at scale 2, 1440p and 4:3 at auto
        // scale: {slot, list x, list width, preview x0, x1, y1, list bottom}.
        int[][] sizes = {{960, 540, 34, 88, 220, 316, 952, 506, 264}, {640, 360, 34, 88, 213, 309, 632, 326, 264},
                {637, 346, 34, 88, 212, 308, 629, 312, 264}, {427, 240, 24, 68, 142, 218, 419, 206, 204}, {320, 240, 24, 68, 136, 212, 312, 206, 204}};
        for (int[] s : sizes) {
            Wardrobe.Layout l = Wardrobe.layout(s[0], s[1]);
            assert l.slot() == s[2] && l.listX() == s[3] && l.listWidth() == s[4] && l.previewX0() == s[5] && l.previewX1() == s[6]
                    && l.previewY1() == s[7] && l.top() == 20 && l.bottom() == s[8] : s[0] + "x" + s[1] + ": " + l;
        }
        // Every GUI size from 320 x 240 to 1440p at scale 1: the slot column fits above the footer and ends with the list, the
        // French "Équipements" heading (60 px) ends before the list, nothing overlaps, the list shows 8 rows of a text line
        // or more, and the preview keeps some room.
        for (int w = 320; w <= 2560; w += 7)
            for (int h = 240; h <= 1440; h += 5) {
                Wardrobe.Layout l = Wardrobe.layout(w, h);
                String at = w + "x" + h + ": " + l;
                int last = 0;
                for (int i = 0; i < Wardrobe.SLOTS.size(); i++) {
                    int[] slot = l.slot(i);
                    assert slot[0] >= Wardrobe.MARGIN && slot[0] + l.slot() <= l.listX() - Wardrobe.MARGIN
                            && slot[1] >= l.heading(Wardrobe.SLOTS.get(i).group()) + 10 : at + ", slot " + i;
                    for (int k = 0; k < i; k++) {
                        int[] other = l.slot(k);
                        assert Math.abs(other[0] - slot[0]) >= l.cell() || Math.abs(other[1] - slot[1]) >= l.cell() : at + ", slots " + k + " and " + i + " overlap";
                    }
                    last = Math.max(last, slot[1] + l.slot());
                }
                assert last == l.bottom() && l.bottom() <= h - Wardrobe.FOOTER && Wardrobe.MARGIN + 60 <= l.listX() : at + ", slot column to " + last;
                assert (l.bottom() - l.top() - 4) / Wardrobe.ROW >= 8 && Wardrobe.ROW - 4 >= 9 : at + ", list rows";
                assert l.listX() + l.listWidth() + Wardrobe.MARGIN == l.previewX0() && l.previewX1() == w - Wardrobe.MARGIN
                        && l.previewX1() - l.previewX0() >= 100 && l.previewY1() - l.top() >= 180 && l.previewY1() + Wardrobe.CAPTION + Wardrobe.FOOTER == h : at + ", preview";
            }
        Wardrobe.Layout big = Wardrobe.layout(960, 540);
        assert big.slot(0)[1] == 30 && big.slot(2)[1] == 30 + 36 + 10 && big.slot(6)[1] == 30 + 36 + 10 + 72 + 10 && big.slot(10)[1] == big.slot(6)[1] + 72
                && big.slot(1)[0] == 8 + 36 : "Exospine, Gear and Look rows under their headings";

        // Icons in screen pixels at a whole ratio of their 64-px texture, the largest that fits, at GUI scales 1 to 6 (auto
        // reaches 6 at 1440p), in WardrobeScreen's boxes: inside both slot sizes, a row's icon, the lock in both, the badge.
        // From scale 2 the slots and rows get 32 px or more; the large slots 64 at scale 2, the rows 32 at 2 and 64 at 4.
        for (int scale = 1; scale <= 6; scale++)
            for (int box : new int[] {34 - 2, 24 - 2, Wardrobe.ROW - 6, 34 / 2, 24 / 2, 9}) {
                int pixels = Wardrobe.icon(box, scale);
                assert 64 % pixels == 0 && pixels <= box * scale && (pixels == 64 || 2 * pixels > box * scale)
                        && (scale < 2 || box < 16 || pixels >= 32) : box + " px at scale " + scale + ": " + pixels;
            }
        assert Wardrobe.icon(32, 2) == 64 && Wardrobe.icon(Wardrobe.ROW - 6, 2) == 32 && Wardrobe.icon(Wardrobe.ROW - 6, 4) == 64 && Wardrobe.icon(3, 1) == 2;

        // The pack's manifest and the stale-pack check against a Paks folder.
        String manifest = "SBAVPACK 1\nPAKS /games/SB/Content/Paks\nSOURCE pakchunk0-WindowsNoEditor.pak 100 200\nSOURCE ~mods/My Mod_P.utoc 5 6\n"
                + "MESH CH_P_EVE_08.sbav SB/Content/Art/CH_P_EVE_08 pakchunk0-WindowsNoEditor\nEND\n";
        Wardrobe.Manifest parsed = Wardrobe.manifest(manifest);
        assert parsed.paks().equals(Path.of("/games/SB/Content/Paks"))
                && parsed.sources().equals(Map.of("pakchunk0-WindowsNoEditor.pak", "100 200", "~mods/My Mod_P.utoc", "5 6")) : parsed;
        for (String bad : new String[] {manifest.replace("SBAVPACK 1", "SBAVPACK 2"), manifest.replace("END\n", ""), manifest.replace("PAKS /games", "PATH /games"),
                manifest.replace(" 5 6", " 5"), manifest.replace("MESH", "BLOB"), manifest.replace("~mods/My Mod_P.utoc", "pakchunk0-WindowsNoEditor.pak"),
                "SBAVPACK 1\nPAKS \nEND\n", null})
            assert Wardrobe.manifest(bad) == null : "Refused: " + bad;
        assert Wardrobe.differences(Map.of("a.pak", "1 2", "b.utoc", "3 4", "c.ucas", "5 6"), Map.of("a.pak", "1 2", "b.utoc", "3 5", "d.pak", "7 8"))
                .equals(List.of("~b.utoc", "-c.ucas", "+d.pak")) : "Changed, removed, added";

        Path root = Files.createTempDirectory("wardrobe-test"), paks = root.resolve("Paks"), pack = root.resolve("avatar");
        try {
            Files.createDirectories(paks.resolve("~mods/My Mod"));
            Files.createDirectories(pack);
            Files.writeString(paks.resolve("pakchunk0-WindowsNoEditor.pak"), "base");
            Files.writeString(paks.resolve("~mods/My Mod/My Mod_P.utoc"), "mod");
            Files.writeString(paks.resolve("readme.txt"), "not a container");
            assert Wardrobe.stalePack(pack) == null : "No manifest: a pack from before mod tracking";
            Map<String, String> found = Wardrobe.sources(paks);
            assert found.keySet().equals(java.util.Set.of("pakchunk0-WindowsNoEditor.pak", "~mods/My Mod/My Mod_P.utoc")) : found;
            if (BridgeRuntimeTest.symlink(root.resolve("Linked"), paks))
                assert Wardrobe.sources(root.resolve("Linked")).equals(found) : "A symlinked Paks folder (a moved library)";
            StringBuilder written = new StringBuilder("SBAVPACK 1\nPAKS " + paks + "\n");
            found.forEach((path, stamp) -> written.append("SOURCE ").append(path).append(' ').append(stamp).append('\n'));
            Files.writeString(pack.resolve("pack.txt"), written.append("END\n"));
            assert Wardrobe.stalePack(pack) == null : "The game files the pack was built from";
            Files.writeString(paks.resolve("~mods/Other_P.ucas"), "added");
            Files.writeString(paks.resolve("~mods/My Mod/My Mod_P.utoc"), "updated");
            String stale = Wardrobe.stalePack(pack);
            assert stale != null && stale.contains("(~~mods/My Mod/My Mod_P.utoc, +~mods/Other_P.ucas)") && stale.contains("rebuild the pack") : stale;
            Files.writeString(pack.resolve("pack.txt"), "SBAVPACK 2\n");
            assert Wardrobe.stalePack(pack).contains("unreadable") : "Another manifest version is not trusted";
            Files.writeString(pack.resolve("pack.txt"), "SBAVPACK 1\nPAKS " + root.resolve("unmounted") + "\nEND\n");
            assert Wardrobe.stalePack(pack) == null : "No Paks folder to compare (its drive is not mounted)";
        } finally {
            try (var walk = Files.walk(root)) { walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.println("PASS wardrobe: pack catalog, slot rule, slot lists, layout over the GUI sizes, icon ratios, pack manifest and stale-pack check");
    }
}

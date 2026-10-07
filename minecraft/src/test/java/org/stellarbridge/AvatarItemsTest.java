package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class AvatarItemsTest {
    public static void main(String[] args) throws Exception {
        String fixture = Files.readString(Path.of("protocol/fixtures/hud.txt"));
        var hud = AvatarItems.parse(fixture);
        assert hud != null && hud.session().equals("a".repeat(32)) && hud.seq() == 7 && hud.time() == 1760000000000L : "The shared fixture parses";
        assert hud.heal().equals(new AvatarItems.Slot("tumbler", 3, 5, 0, true)) : hud.heal();
        assert hud.util().equals(new AvatarItems.Slot("shock", 2, 0, 0.4f, true)) : hud.util();
        assert hud.ammoAlias().equals("Bullet_Normal") && hud.ammo() == 120 && hud.ammoCooldown() == 0 && hud.dash() == 0.25f;
        assert Arrays.equals(hud.uses(), new int[] {1, 0, 0, 0});
        var none = AvatarItems.parse(fixture.replaceAll("HEAL .*\n", "HEAL none\n").replaceAll("UTIL .*\n", "UTIL none\n")
                .replaceAll("AMMO .*\n", "AMMO none\n").replace("DASH 0.25", "DASH none"));
        assert none != null && none.heal() == null && none.util() == null && none.ammoAlias() == null && Float.isNaN(none.dash()) : "Every slot may be none";
        assert AvatarItems.parse(fixture.replace("USES 1 0 0 0", "USES 2147483647 0 0 0")).uses()[0] == Integer.MAX_VALUE;
        for (String bad : new String[] {fixture.replace("SBMC_HUD 1", "SBMC_HUD 2"), fixture.replace("END 7", "END 8"),
                fixture.replace("UTIL shock", "UTIL rocket"), fixture.replace("0.40", "1.50"), fixture.replace("0.40", "nan"),
                fixture.replace("UTIL shock 2", "UTIL shock -1"), fixture.replace("USES 1 0 0 0\n", ""), fixture + "EXTRA\n",
                fixture.replace("SESSION a", "SESSION A"), fixture.replace("USES 1 0 0 0", "USES 2147483648 0 0 0"),
                fixture.replace("AMMO Bullet_Normal", "AMMO Bullet-Normal"), fixture.replace("HEAL tumbler 3 5 1", "HEAL tumbler 3 5 2"),
                fixture.substring(0, fixture.length() - 1), null})
            assert AvatarItems.parse(bad) == null : "Refused: " + bad;

        String session = "b".repeat(32), older = fixture.replace("a".repeat(32), session), newer = older.replace("SEQ 7", "SEQ 9").replace("END 7", "END 9");
        assert AvatarItems.select(older, newer, session).seq() == 9 && AvatarItems.select(newer, older, session).seq() == 9 : "The higher SEQ wins";
        assert AvatarItems.select(null, older, session).seq() == 7 && AvatarItems.select("garbage", older, session).seq() == 7 : "One valid slot is enough";
        assert AvatarItems.select(older, newer, "c".repeat(32)) == null : "Another session's slots are not this link's";

        assert Arrays.equals(AvatarItems.used(null, new int[] {1, 0, 0, 0}), new int[4]) : "A first sighting converts nothing";
        assert Arrays.equals(AvatarItems.used(new int[] {1, 0, 0, 0}, new int[] {2, 0, 0, 0}), new int[] {1, 0, 0, 0});
        assert Arrays.equals(AvatarItems.used(new int[] {1, 0, 2, 0}, new int[] {1, 0, 5, 0}), new int[] {0, 0, 3, 0});
        assert Arrays.equals(AvatarItems.used(new int[] {1, 0, 0, 0}, new int[] {6, 0, 0, 0}), new int[4]) : "A step of 5 is garbage";
        assert Arrays.equals(AvatarItems.used(new int[] {4, 0, 0, 0}, new int[] {1, 0, 0, 0}), new int[4]) : "A decrease is a Lua reload";

        assert AvatarItems.fresh(1000, 3500) && AvatarItems.fresh(3500, 1000) : "2500 ms is still fresh";
        assert !AvatarItems.fresh(1000, 3501) && !AvatarItems.fresh(3501, 1000);
        System.out.println("PASS avatar items: shared HUD fixture, refused grammar, newest session slot, use steps, freshness");
    }
}

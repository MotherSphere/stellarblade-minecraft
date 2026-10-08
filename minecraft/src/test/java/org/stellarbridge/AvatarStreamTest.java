package org.stellarbridge;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import org.joml.Matrix4d;

/** Pose stream table grammar, the pose mapping against a simulated native producer, and component-space skinning. */
public final class AvatarStreamTest {
    static final String RUN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { interop(Path.of(args[0]), Integer.parseInt(args[1])); return; }
        String fixture = Files.readString(Path.of("protocol/fixtures/pose-components.txt"));
        AvatarStream.Table table = AvatarStream.parse(fixture);
        assert table != null && table.generation() == 1 && table.anchor() == null && table.session().equals("none") && !table.controller();
        String controller = "CONTROLLER 00007f0000005000 00007f0000006000 00000318 00007f0000007000 00007f0000008000 2.5 -2.5";
        assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller)).controller();
        for (String scales : new String[] {"2.5 0", "NaN -2.5", "2.5 1e9", "2.5 0x1p1", "2.5"})
            assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller.replace("2.5 -2.5", scales))) == null;
        for (String offset : new String[] {"00000319", "00004000", "00000000", "318"})
            assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller.replace("00000318", offset))) == null;
        assert table.floats() == 52 && table.components().size() == 2;
        // Material parameters follow the components in the table and in the payload.
        String glowLine = "PARAMETER 1 1 Edge Opacity 00007f0000009000 00007f000000a000 00000168 2 4711 1\n";
        var glowing = AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "PARAMETERS 1\n" + glowLine));
        assert glowing != null && glowing.parameters().size() == 1 && glowing.floats() == 53 && glowing.componentFloats() == 52;
        for (String bad : new String[] {glowLine.replace(" 1 1 Edge", " 1 3 Edge"), glowLine.replace("00000168", "00000164"),
                glowLine.replace(" 4711 1", " 4711 2"), glowLine.replace(" 4711 1", " 0 1"), glowLine.replace(" 2 4711", " 1024 4711")})
            assert AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "PARAMETERS 1\n" + bad)) == null : bad;
        assert AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "")) == null : "The parameter count is required";
        float[] glowPayload = new float[53];
        glowPayload[52] = 0.75f;
        var glowSample = new AvatarStream.Sample(1, 1, 1, new int[] {7, 5}, glowPayload, null, false, Float.NaN, false, 0);
        assert AvatarStream.parameters(glowing, glowSample).get("Edge/Opacity")[0] == 0.75f;
        // Effects: the layout line and its assets, then MAX_EFFECTS records after the parameters.
        String effectLines = "EFFECTS 00000130 000006a8 000004b0 00000088 10 00007f000000b000 00007f000000c000 1\n"
                + "EFFECT 1 00007f000000d000 NS_A_Weapon_Signal_Sword\n";
        var sparkling = AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines));
        assert sparkling != null && sparkling.effects().equals(java.util.List.of("NS_A_Weapon_Signal_Sword")) && sparkling.effectRecords()
                && sparkling.floats() == 52 + AvatarStream.MAX_EFFECTS * AvatarStream.EFFECT_FLOATS && sparkling.componentFloats() == 52;
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines.replace(" 10 ", " 00 "))) == null : "A zero mask";
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines.replace(" 1\nEFFECT", " 2\nEFFECT"))) == null : "Missing asset";
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", "")) == null : "The effects line is required";
        // CLOTH names the component (1-based) whose finished cloth follows the payload, or none; the line is required.
        assert table.cloth() == -1 && AvatarStream.parse(fixture.replace("CLOTH none", "CLOTH 2")).cloth() == 1;
        for (String bad : new String[] {"CLOTH 0", "CLOTH 3", "CLOTH", "CLOTH +1", "CLOTH 02", "CLOTH 1 2", "CLOTH -1"})
            assert AvatarStream.parse(fixture.replace("CLOTH none", bad)) == null : bad;
        assert AvatarStream.parse(fixture.replace("CLOTH none\n", "")) == null : "The cloth line is required";
        // A component carries up to AvatarMesh.MAX_BONES bones (suits up to 364), as the adapter parses it.
        for (int bones : new int[] {AvatarMesh.MAX_BONES, AvatarMesh.MAX_BONES + 1}) {
            var wide = AvatarStream.parse(fixture.replace(" 3 0 CharacterMesh0 ", " " + bones + " 0 CharacterMesh0 ")
                    .replace("BONES 1 Root Bip001 Bip001-Head", "BONES 1" + " b".repeat(bones)));
            assert bones == 512 ? wide != null && wide.floats() == 10 + 10 * 512 + 12 && wide.components().get(0).bones().length == 512
                    : wide == null : bones + " bones";
        }
        float[] effectPayload = new float[sparkling.floats()];
        java.util.Arrays.fill(effectPayload, 52, effectPayload.length, 0);
        for (int r = 0; r < AvatarStream.MAX_EFFECTS; r++) effectPayload[52 + r * AvatarStream.EFFECT_FLOATS] = -1;
        effectPayload[52] = 0;
        effectPayload[52 + 5] = 100;
        effectPayload[52 + 7] = 300;
        var effects = AvatarStream.effects(sparkling, new AvatarStream.Sample(1, 1, 1, new int[] {7, 5}, effectPayload, null, false, Float.NaN, false, 0));
        assert effects.size() == 1 && effects.get(0).name().equals("NS_A_Weapon_Signal_Sword") && effects.get(0).x() == 100 && effects.get(0).z() == 300;
        assert AvatarEffects.look("NS_A_Weapon_Signal_Sword") == AvatarEffects.Look.SIGNAL && AvatarEffects.look("P_A_AnimTrail_SwordLightAtk_01") == null
                && AvatarEffects.look("NS_A_ParTrail_SwordDefaultAtk_01") == AvatarEffects.Look.SPARKS
                && AvatarEffects.look("NS_PC_SpinTrail_01") == AvatarEffects.Look.SWEEP && AvatarEffects.look("NS_Unknown") == AvatarEffects.Look.GLINT;
        // Sprite recipes and trail styles follow the game's effect assets.
        assert AvatarSprites.recipe("P_A_AnimTrail_SwordLightAtk_01").isEmpty() : "Cascade trails are the ribbon's";
        assert AvatarSprites.recipe("NS_A_ParTrail_SwordDefaultAtk_01").getFirst().shape() == AvatarSprites.Shape.STREAK;
        var signal = AvatarSprites.recipe("NS_A_HoldBurst_Signal");
        assert signal.size() == 4 && signal.getFirst().shape() == AvatarSprites.Shape.HORIZON && signal.getFirst().r() > signal.getFirst().g() * 5
                : "The charged attack's signal is a red horizontal flare first";
        assert AvatarSprites.recipe("NS_A_Weapon_Electric_01").getFirst().place() == AvatarSprites.Place.BLADE;
        assert AvatarTrail.Style.of("P_A_AnimTrail_SwordStrongAtk_01") == AvatarTrail.Style.STRONG
                && AvatarTrail.Style.of("P_A_AnimTrail_SwordThrust_01") == AvatarTrail.Style.THRUST
                && AvatarTrail.Style.of("P_A_AnimTrail_SwordLightAtk_01") == AvatarTrail.Style.LIGHT && AvatarTrail.Style.of("NS_A_KickCharge") == null;
        // Combat: the defense at an impact from the latest presses, strike damage, and the blade's sweep through a box.
        long impact = 10_000_000_000L;
        assert AvatarCombat.defense(impact, impact - 200_000_000L, Long.MIN_VALUE, false) == AvatarCombat.Defense.PERFECT_DODGE
                : "A dodge at 2.8 s against an attack landing at 3 s";
        assert AvatarCombat.defense(impact, impact - 400_000_000L, Long.MIN_VALUE, false) == AvatarCombat.Defense.DODGE;
        assert AvatarCombat.defense(impact, impact - 900_000_000L, Long.MIN_VALUE, true) == AvatarCombat.Defense.GUARD;
        assert AvatarCombat.defense(impact, impact - 200_000_000L, impact - 100_000_000L, true) == AvatarCombat.Defense.PERFECT_PARRY;
        assert AvatarCombat.defense(impact, Long.MIN_VALUE, Long.MIN_VALUE, false) == AvatarCombat.Defense.HIT;
        assert AvatarCombat.defense(impact, impact + 1, Long.MIN_VALUE, false) == AvatarCombat.Defense.HIT : "A press after the impact";
        // Stellar Blade's parry tiers: a just parry, a parry up to its Parry1 step, then a held guard or nothing.
        assert AvatarCombat.defense(impact, Long.MIN_VALUE, impact - 300_000_000L, false) == AvatarCombat.Defense.PARRY : "A tapped guard parries";
        assert AvatarCombat.defense(impact, Long.MIN_VALUE, impact - 450_000_000L, true) == AvatarCombat.Defense.GUARD;
        assert AvatarCombat.defense(impact, Long.MIN_VALUE, impact - 450_000_000L, false) == AvatarCombat.Defense.HIT;
        assert AvatarCombat.defense(impact, impact - 400_000_000L, impact - 300_000_000L, true) == AvatarCombat.Defense.DODGE : "A dodge outranks a late parry";
        assert AvatarCombat.event(AvatarCombat.Defense.GUARD, AvatarCombat.STRONG_HIT).equals("guard_strong")
                && AvatarCombat.event(AvatarCombat.Defense.GUARD, 7.9f).equals("guard")
                && AvatarCombat.event(AvatarCombat.Defense.PERFECT_PARRY, 20).equals("perfect_parry")
                && AvatarCombat.event(AvatarCombat.Defense.HIT, AvatarCombat.STRONG_HIT).equals("hit_strong")
                && AvatarCombat.event(AvatarCombat.Defense.HIT, 7.9f).equals("hit");
        // The guard's cooldown: a press sooner than 0.4 s after the previous press does not count.
        long first = impact - 1_000_000_000L;
        assert AvatarCombat.counted(Long.MIN_VALUE, Long.MIN_VALUE, first) == first : "A first press counts";
        assert AvatarCombat.counted(first, first, first) == first : "The same press again";
        assert AvatarCombat.counted(first, first, first + 300_000_000L) == first : "Mashing the guard";
        assert AvatarCombat.counted(first, first + 300_000_000L, first + 600_000_000L) == first : "Each press restarts the cooldown";
        assert AvatarCombat.counted(first, first + 300_000_000L, first + 700_000_000L) == first + 700_000_000L;
        assert AvatarCombat.counted(first, first, Long.MIN_VALUE) == Long.MIN_VALUE : "Released input";
        // Against an attack each second: mashing every 0.2 s (guard held) parries none; a tap 0.1 s before each parries all.
        long mashed = Long.MIN_VALUE, seen = Long.MIN_VALUE, tapped = Long.MIN_VALUE, tapSeen = Long.MIN_VALUE;
        int mashParries = 0, tapParries = 0;
        for (long t = 0; t < 10_000_000_000L; t += 50_000_000L) {
            if (t % 200_000_000L == 0) { mashed = AvatarCombat.counted(mashed, seen, t); seen = t; }
            if (t % 1_000_000_000L == 800_000_000L) { tapped = AvatarCombat.counted(tapped, tapSeen, t); tapSeen = t; }
            if (t % 1_000_000_000L == 900_000_000L) {
                var mash = AvatarCombat.defense(t, Long.MIN_VALUE, mashed, true);
                var tap = AvatarCombat.defense(t, Long.MIN_VALUE, tapped, false);
                if (mash == AvatarCombat.Defense.PERFECT_PARRY || mash == AvatarCombat.Defense.PARRY) mashParries++;
                if (tap == AvatarCombat.Defense.PERFECT_PARRY) tapParries++;
            }
        }
        assert mashParries == 0 && tapParries == 10 : "Mashing parries " + mashParries + ", taps " + tapParries;
        assert AvatarCombat.damage(AvatarTrail.Style.STRONG, true) == 2 * AvatarCombat.STRONG && AvatarCombat.damage(null, false) == AvatarCombat.SWING;
        var zombie = new net.minecraft.util.math.Box(-0.3, 0, -0.3, 0.3, 1.95, 0.3);
        assert !AvatarCombat.sweeps(zombie, new org.joml.Vector3d(-1, 1, -1), new org.joml.Vector3d(-1, 1, -2),
                new org.joml.Vector3d(1, 1, -1), new org.joml.Vector3d(1, 1, -2)) : "A swing in front of the box";
        assert AvatarCombat.sweeps(zombie, new org.joml.Vector3d(-0.5, 1, 1), new org.joml.Vector3d(-0.5, 1, -1),
                new org.joml.Vector3d(0.5, 1, 1), new org.joml.Vector3d(0.5, 1, -1)) : "A blade crossing the box between two frames";
        // EVE's body moves an entity inside it out along the line between them, at most SHOVE, and leaves the rest alone.
        var eve = AvatarCombat.body(net.minecraft.util.math.Vec3d.ZERO);
        var east = new net.minecraft.util.math.Box(0.3, 0, -0.3, 0.9, 1.95, 0.3); // A zombie 0.6 blocks east of her feet.
        var out = AvatarCombat.shove(eve, east, AvatarCombat.SHOVE);
        assert Math.abs(out.x - 0.1) < 1e-3 && out.y == 0 && Math.abs(out.z) < 1e-9 && !eve.intersects(east.offset(out)) : out;
        var slanted = east.offset(-0.2, 0, 0.4); // Nearest corner (0.1, 0.1): out along the diagonal, to her cylinder's edge.
        out = AvatarCombat.shove(eve, slanted, AvatarCombat.SHOVE);
        assert Math.abs(out.x - out.z) < 1e-9 && out.x > 0 && AvatarCombat.shove(eve, slanted.offset(out), 1).lengthSquared() == 0
                && AvatarCombat.shove(eve, slanted.offset(out.multiply(0.99)), 1).lengthSquared() > 0 : out;
        var corner = new net.minecraft.util.math.Box(0.283, 0, 0.283, 0.883, 1.95, 0.883); // In her square, outside her cylinder.
        assert eve.intersects(corner) && AvatarCombat.shove(eve, corner, 1).lengthSquared() == 0 : "A corner off her cylinder is outside";
        out = AvatarCombat.shove(eve, east.offset(-0.6, 0, 0), AvatarCombat.SHOVE);
        assert out.x == AvatarCombat.SHOVE && out.z == 0 : "Centred on her: +X, at most SHOVE a tick";
        assert AvatarCombat.shove(eve, east.offset(0.1, 0, 0), 1).lengthSquared() == 0 : "Touching her is outside";
        assert AvatarCombat.shove(eve, east.offset(0, AvatarCombat.BODY_HEIGHT, 0), 1).lengthSquared() == 0 : "Above her head";
        // Her drone's shots hit the first box along the aim, up to its end (the range, or a block that stopped it).
        var eyes = new net.minecraft.util.math.Vec3d(0, 1, 0);
        var aim = new net.minecraft.util.math.Vec3d(10, 1, 0);
        var boxes = java.util.List.of(new net.minecraft.util.math.Box(4, 0, -1, 5, 2, 1), new net.minecraft.util.math.Box(2, 0, -1, 3, 2, 1),
                new net.minecraft.util.math.Box(-3, 0, -1, -2, 2, 1), new net.minecraft.util.math.Box(2, 3, -1, 3, 4, 1));
        assert AvatarCombat.firstHit(eyes, aim, boxes) == 1 : "The nearest box on the aim, not the list's first";
        assert AvatarCombat.firstHit(eyes, new net.minecraft.util.math.Vec3d(1.5, 1, 0), boxes) == -1 : "Beyond the aim's end";
        assert AvatarCombat.firstHit(new net.minecraft.util.math.Vec3d(-2.5, 1, 0), aim, boxes) == 2 : "A box around the eyes at once";
        assert AvatarCombat.firstHit(eyes, aim, java.util.List.of()) == -1 && AvatarCombat.firstHit(eyes, aim, boxes.subList(2, 4)) == -1
                : "Behind her or above the aim";
        // New shots from the adapter's count: none for the first count seen, at most BURST in a frame.
        assert AvatarCombat.shots(-1, 5) == 0 && AvatarCombat.shots(5, 5) == 0 && AvatarCombat.shots(5, 7) == 2
                && AvatarCombat.shots(5, 100) == AvatarCombat.BURST && AvatarCombat.shots(7, 3) == 0;
        assert AvatarCombat.SHOT < AvatarCombat.LIGHT && AvatarCombat.RANGE == 30;
        // Stellar Blade's walls: the eight nearest her feet, clipped to the patch, relative to the origin, no slivers.
        var patch = new net.minecraft.util.math.Box(-3.5, 62, -3.5, 5.5, 68, 5.5);
        var origin = new net.minecraft.util.math.Vec3d(1, 64, 1);
        var around = new java.util.ArrayList<net.minecraft.util.math.Box>();
        for (int i = 9; i >= 1; i--) around.add(new net.minecraft.util.math.Box(1 + i * 0.3, 64, 0.7, 1.6 + i * 0.3, 65.95, 1.3));
        var walls = StellarBridge.mobWalls(patch, origin, origin, around);
        assert walls.size() == 8 && Math.abs(walls.get(0).minX() - 0.3) < 1e-9 && walls.get(0).minY() == 0
                && Math.abs(walls.get(7).minX() - 2.4) < 1e-9 : "The eight nearest, nearest first, relative to the origin";
        walls = StellarBridge.mobWalls(patch, origin, origin, java.util.List.of(new net.minecraft.util.math.Box(5, 64, 1, 6, 66, 2),
                new net.minecraft.util.math.Box(5.495, 64, 3, 6, 66, 4)));
        assert walls.size() == 1 && walls.get(0).maxX() == 4.5 : "Clipped to the patch; a sliver past its edge is dropped";
        // Stellar Blade's HUD: a bar's lit columns, a gauge's cubes and quarters.
        assert AvatarHud.lit(20, 20, 27) == 27 && AvatarHud.lit(1, 20, 27) == 2 && AvatarHud.lit(0, 20, 27) == 0 && AvatarHud.lit(5, 0, 27) == 0;
        assert java.util.Arrays.equals(AvatarHud.cubes(450, 1000, 16), new int[] {5, 2, 1}) : "Two cubes, then a quarter of the third";
        assert java.util.Arrays.equals(AvatarHud.cubes(130, 1600, 20), new int[] {8, 0, 2}) : "A perfect dodge's 130 Burst";
        assert java.util.Arrays.equals(AvatarHud.cubes(1000, 1000, 16), new int[] {5, 5, 0}) && AvatarHud.cubes(0, 5000, 20)[0] == 20;
        // The Tachy ring: from the actor stats once Stellar Blade unlocked it, only for sane values.
        float[] actor = new float[AvatarStream.ACTOR_STATS];
        actor[22] = 9000;
        actor[23] = 18000;
        assert AvatarHud.tachy(null) == null && AvatarHud.tachy(actor) == null : "Locked Tachy has no ring";
        actor[117] = 1;
        assert java.util.Arrays.equals(AvatarHud.tachy(actor), new float[] {9000, 18000});
        actor[22] = 18001;
        assert AvatarHud.tachy(actor) == null : "Tachy above its maximum";
        actor[22] = Float.NaN;
        assert AvatarHud.tachy(actor) == null : "Non-finite Tachy";
        actor[22] = -1;
        assert AvatarHud.tachy(actor) == null : "Negative Tachy";
        actor[22] = 9000;
        actor[23] = 200000;
        assert AvatarHud.tachy(actor) == null : "An implausible maximum";
        actor[23] = 18000;
        actor[117] = Float.NaN;
        assert AvatarHud.tachy(actor) == null : "A non-finite unlock is locked";
        // The panel's scale (screen pixels per panel pixel) and HP columns, the chat's bottom, the HP length, the shield row.
        assert java.util.Arrays.equals(AvatarHud.layout(960, 2, 77, 109), new int[] {2, 77}) : "Full size where there is room";
        assert java.util.Arrays.equals(AvatarHud.layout(480, 4, 77, 109), new int[] {2, 56}) : "Half size at 1080p's auto scale, HP shortened";
        assert java.util.Arrays.equals(AvatarHud.layout(640, 3, 77, 109), new int[] {3, 45}) && java.util.Arrays.equals(AvatarHud.layout(427, 6, 77, 109), new int[] {3, 38});
        assert java.util.Arrays.equals(AvatarHud.layout(480, 4, 77, 177), new int[] {2, 56}) : "Never below half the GUI scale";
        assert java.util.Arrays.equals(AvatarHud.layout(320, 4, 77, 109), new int[] {2, 10}) : "At least 10 HP columns";
        assert java.util.Arrays.equals(AvatarHud.layout(512, 5, 77, 177), new int[] {3, 52}) : "Half an odd GUI scale rounds up";
        assert AvatarHud.widest(14, 9, 2) == 109 && AvatarHud.widest(0, 0, 0) == 68 : "The user's maxima; HP's 23 columns";
        assert AvatarHud.widest(14, 0, 3) == 141 && java.util.Arrays.equals(AvatarHud.layout(640, 3, 77, 141), new int[] {2, 77})
                : "The gold absorption block past three shield blocks keeps the panel off the offhand slot";
        assert AvatarHud.hpColumns(2000) == 23 && AvatarHud.hpColumns(4000) == 47 && AvatarHud.hpColumns(6500) == 77 && AvatarHud.hpColumns(1e6f) == 77;
        assert AvatarHud.hpColumns(2240) == 23 && AvatarHud.hpColumns(2260) == 29 && AvatarHud.hpColumns(0) == 23 : "Nearest Body level, at least 0";
        assert AvatarHud.chatBottom(2, 2) == 70 && AvatarHud.chatBottom(2, 4) == 40 && AvatarHud.chatBottom(3, 5) == 44;
        actor[7] = 500;
        actor[8] = 500;
        actor[11] = 2;
        assert java.util.Arrays.equals(AvatarHud.shield(actor), new float[] {2, 2, 1}) : "EVE's base shield: two full blocks of 250";
        actor[7] = 250;
        assert java.util.Arrays.equals(AvatarHud.shield(actor), new float[] {2, 1, 1}) : "Exactly one block full";
        actor[7] = 300;
        assert java.util.Arrays.equals(AvatarHud.shield(actor), new float[] {2, 2, 0.2f}) : "The second block a fifth full";
        actor[7] = 0;
        assert java.util.Arrays.equals(AvatarHud.shield(actor), new float[] {2, 0, 0}) : "Broken";
        actor[11] = 7;
        assert AvatarHud.shield(actor)[0] == 5 : "At most five blocks";
        actor[7] = 500;
        assert java.util.Arrays.equals(AvatarHud.shield(actor), new float[] {5, 5, 1}) : "Lit blocks capped at the five shown";
        actor[11] = Float.NaN;
        assert AvatarHud.shield(null) == null && AvatarHud.shield(actor) == null : "Unread or non-finite";
        actor[11] = 2;
        actor[7] = 501;
        assert AvatarHud.shield(actor) == null : "Shield above its maximum";
        // Rings sweep clockwise from 12 o'clock (y down).
        assert AvatarHud.arc(0.5f, -9, 0.01f) && AvatarHud.arc(0, 9, 0.6f) && !AvatarHud.arc(0, 9, 0.4f) && !AvatarHud.arc(-9, 0, 0.7f);
        // Gauge points Minecraft counts for Stellar Blade (the adapter applies her rates and caps).
        assert java.util.Arrays.equals(AvatarCombat.gains(false, false, false, false, false), new int[] {15, 0}) : "A sword hit";
        assert java.util.Arrays.equals(AvatarCombat.gains(false, false, true, false, false), new int[] {19, 0}) : "A critical hit";
        assert java.util.Arrays.equals(AvatarCombat.gains(true, true, false, false, false), new int[] {0, 200}) : "A charged finisher gives Burst";
        assert java.util.Arrays.equals(AvatarCombat.gains(true, true, true, false, false), new int[] {4, 200});
        assert java.util.Arrays.equals(AvatarCombat.gains(true, false, false, false, false), new int[] {0, 0}) : "Once per entity and activation";
        assert java.util.Arrays.equals(AvatarCombat.gains(false, false, true, true, false), new int[] {0, 0}) : "A drone shot gives nothing";
        assert java.util.Arrays.equals(AvatarCombat.gains(false, false, false, false, true), new int[] {0, 0}) : "Nothing during a skill";
        assert AvatarCombat.killBurst(true, false, 300) == 200 && AvatarCombat.killBurst(false, true, 100) == 100
                && AvatarCombat.killBurst(false, true, 20) == 30 && AvatarCombat.killBurst(false, false, 10) == 6 : "Kill ranks";
        assert AvatarCombat.killBurst(false, true, 50) == 100 : "An elite from 50 maximum health";
        assert AvatarCombat.spent(new float[] {1000, 1000, 0, 1600, 1}, new float[] {600, 1000, 0, 1600, 1}) : "A Beta skill";
        assert !AvatarCombat.spent(new float[] {1000, 1000, 0, 1600, 1}, new float[] {800, 1000, 0, 1600, 1}) : "An Ex finisher's 200 is not a skill";
        assert AvatarCombat.spent(new float[] {0, 1000, 1600, 1600, 1}, new float[] {0, 1000, 1000, 1600, 1}) : "A Burst skill";
        assert !AvatarCombat.spent(new float[] {0, 1000, 1600, 1600, 1}, new float[] {0, 1000, 1400, 1600, 1});
        assert !AvatarCombat.spent(null, new float[] {0, 1000, 0, 1600, 1}) : "No gauges before";
        assert AvatarCombat.spent(new float[] {1000, 1000, 0, 1600, 1}, new float[] {700, 1000, 0, 1600, 1}) : "A drop of 300 or more";
        // The quiet window: a spent gauge opens it, across a frame without gauges; a smaller drop does not.
        long t = System.nanoTime() + AvatarCombat.QUIET; // After the class loaded, in its clock's range.
        assert !AvatarCombat.quiet(t);
        AvatarCombat.watch(new float[] {1000, 1000, 0, 1600, 1}, t);
        AvatarCombat.watch(null, t + 1);
        AvatarCombat.watch(new float[] {600, 1000, 0, 1600, 1}, t + 2);
        assert AvatarCombat.quiet(t + 3) && !AvatarCombat.quiet(t + 2 + AvatarCombat.QUIET) : "A spent gauge quiets strikes, across an unread frame";
        AvatarCombat.watch(new float[] {400, 1000, 0, 1600, 1}, t + 3 * AvatarCombat.QUIET);
        assert !AvatarCombat.quiet(t + 3 * AvatarCombat.QUIET + 1) : "An Ex finisher's 200 does not";
        // Stellar Blade's heals as Minecraft health: a u32 step of the adapter's count, the same share of the maximum.
        assert AvatarStats.heals(-1, 5) == 0 : "A first count is a baseline";
        assert AvatarStats.heals(5, 1205) == 1200 && AvatarStats.heals(0xFFFFFFF0L, 0x10) == 0x20 : "u32 wrap";
        assert AvatarStats.heals(0, 200000) == 0 : "A garbage step is not a heal";
        assert AvatarStats.heals(0, 100000) == 100000 && AvatarStats.heals(0, 100001) == 0 : "The largest heal step";
        assert AvatarStats.healthFor(1200, 2000, 20) == 12f && AvatarStats.healthFor(1, 0, 20) == 20f;
        // Each tick takes the reported count while EVE drives the player; a lapsed link leaves it waiting, unacknowledged.
        AvatarStats.reportHeals(1000);
        assert AvatarStats.due(true, true) == 0 : "A first count is a baseline";
        AvatarStats.reportHeals(2200);
        assert AvatarStats.due(false, true) == 0 && AvatarStats.due(true, true) == 1200 : "A heal waits out a lapsed link";
        AvatarStats.reportHeals(2500);
        assert AvatarStats.due(false, false) == 0 && AvatarStats.due(true, true) == 0 : "Death takes the heals";
        AvatarStats.reportHeals(-1);
        AvatarStats.due(true, true);
        AvatarStats.reportHeals(3000);
        assert AvatarStats.due(true, true) == 0 : "A stopped stream forgets the count: the next one is a baseline";
        // Stellar Blade's stats as Minecraft's: her base is vanilla's, upgrades scale health and strikes, reductions multiply.
        var base = AvatarStats.Values.of(new float[] {2000, 2, 450, 5, 1.5f, 0, 0, 0});
        assert base.health() == 1 && base.strikes() == 1 && Math.abs(base.taken(false) - 0.65f) < 1e-6 : "Two shield blocks take 35 %";
        assert base.crits(0.04f) && !base.crits(0.05f) && base.critMultiplier() == 1.5f : "5 % of strikes deal 150 %";
        var upgraded = AvatarStats.Values.of(new float[] {4000, 3, 900, 5, 1.5f, 0.2f, 0.1f, 0});
        assert upgraded.health() == 2 && upgraded.strikes() == 2;
        assert Math.abs(upgraded.taken(false) - 0.8f * 0.475f) < 1e-6 && Math.abs(upgraded.taken(true) - 0.9f * 0.475f) < 1e-6
                : "Melee and ranged reductions by the hit's kind, times the shield's";
        var strange = AvatarStats.Values.of(new float[] {99999, 99, 1, 500, 99, 5, -5, -5});
        assert strange.health() == AvatarStats.MAX_SCALE && strange.strikes() == AvatarStats.MIN_SCALE
                && strange.critMultiplier() == AvatarStats.MAX_SCALE && strange.crits(0.99f);
        assert Math.abs(strange.taken(false) - 0.1f * 2 * 0.1f) < 1e-6 : "Each reduction stops at 90 %, a negative final one doubles at most";
        assert Math.abs(strange.taken(true) - 2 * 0.1f) < 1e-6 : "A negative ranged reduction adds nothing";
        var none = AvatarStats.Values.BASE;
        assert none.health() == 1 && none.strikes() == 1 && none.taken(true) == 1 && !none.crits(0) && none.critMultiplier() == 1
                : "Without stats, Minecraft's own rules";
        assert AvatarStats.status(null).equals(" Stats not read.") && AvatarStats.status(new float[] {2000, 2, 450, 5, 1.5f, 0, 0, 0})
                .contains("max HP 2000 (health x1");
        var body = table.components().get(0);
        var face = table.components().get(1);
        assert body.recorded() && body.bones().length == 3 && body.bones()[2].equals("Bip001-Head") && body.weights() == 0;
        assert !face.recorded() && face.leader().equals("CharacterMesh0") && face.weights() == 2 && face.morphs()[1].equals("blink");
        String anchored = fixture.replace("SESSION none\nANCHOR none", "SESSION " + "b".repeat(32) + "\nANCHOR 1.5 -2 3 100");
        assert AvatarStream.parse(anchored).anchor()[3] == 100 && AvatarStream.parse(anchored).anchor()[0] == 1.5;
        for (String bad : new String[] {fixture.replace("BONES 1 Root Bip001 Bip001-Head", "BONES 1 Root Bip001"),
                fixture.replace("COUNT 2", "COUNT 3"), fixture.replace("END 1", "END 2"), fixture.substring(0, fixture.length() - 1),
                fixture.replace("ANCHOR none", "ANCHOR 1 2 3 0"), fixture.replace("00007f0000001000", "7f0000001000"),
                fixture.replace(" 3 0 CharacterMesh0", " 3 0 CharacterMesh0").replace("CH_P_EVE_52_TypeB -", "CH_P_EVE_52_TypeB Leader")})
            assert AvatarStream.parse(bad) == null : bad;
        String second = fixture.replace("GENERATION 1", "GENERATION 2").replace("END 1", "END 2");
        assert AvatarStream.select(fixture, second, RUN).generation() == 2 && AvatarStream.select(second, null, RUN).generation() == 2;
        assert AvatarStream.select(fixture.replace(RUN, "c".repeat(32)), null, RUN) == null : "Another run's table is ignored";

        // Steering: the left stick turns by the actual angle, the right stick closes the (unwrapped) error.
        assert java.util.Arrays.equals(ControlBridge.steer(0, 0, 32767), new int[] {0, 32767, 0});
        assert java.util.Arrays.equals(ControlBridge.steer(90, 0, 32767), new int[] {32767, 0, 32767}) : "Camera 90 degrees left: forward is stick right";
        assert java.util.Arrays.equals(ControlBridge.steer(-90, 0, 32767), new int[] {-32767, 0, -32767});
        assert ControlBridge.steer(10, 0, 0)[2] == (int) Math.round(10 / ControlBridge.STEER_DEGREES * 32767);
        assert ControlBridge.steer(0.5, 0, 0)[2] == 0 : "A sub-degree error is not chased";
        int[] diagonal = ControlBridge.steer(45, 23170, 23170);
        assert Math.abs(diagonal[0] - 32767) <= 1 && Math.abs(diagonal[1]) <= 1 : "Rotation keeps the diagonal's length";
        int[] behind = ControlBridge.steer(270, 0, 32767);
        assert behind[0] == -32767 && behind[2] == 32767 : "Movement uses the real angle while the camera keeps turning right";
        // Yaw error: Minecraft yaw + 90 is Unreal yaw; the start takes the shortest way, a fast spin never flips direction.
        ControlBridge.resetSteering();
        assert Math.abs(ControlBridge.yawError(350, 80) - 0) < 1e-9 : "Angles wrap at the start";
        ControlBridge.resetSteering();
        assert Math.abs(ControlBridge.yawError(0, 350) - 100) < 1e-9;
        double unreal = 350, minecraft = 0, error = 0;
        for (int frame = 0; frame < 20; frame++) { // Minecraft spins right 40 degrees per frame, Stellar Blade 10.
            minecraft += 40;
            unreal = (unreal + 10) % 360;
            error = ControlBridge.yawError(minecraft, unreal);
            assert error > 0 : "The camera never reverses during a fast right spin (frame " + frame + ", error " + error + ")";
        }
        assert error < 360 : "At most one turn behind";

        // Display interpolation between two poses.
        var single = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(new AvatarRecording.Component("C", "M", null,
                new String[] {"Root"}, new String[] {"m"}, 1)), java.util.List.of(), java.util.List.of(), false, 21);
        var start = AvatarStream.emptyPose(single);
        var end = AvatarStream.emptyPose(single);
        var mid = AvatarStream.emptyPose(single);
        start.world()[0][3] = end.world()[0][3] = 1; start.local()[0][3] = end.local()[0][3] = 1;
        end.world()[0][4] = 10; end.weights()[0][0] = 1;
        AvatarView.blend(start, end, 0.25f, mid);
        assert mid.world()[0][4] == 2.5f && mid.weights()[0][0] == 0.25f && mid.world()[0][3] == 1;
        // Cloth positions interpolate linearly into a reused array; with one end on bones, bones for the interval.
        start.cloth()[0] = new float[] {0, 0, 0};
        end.cloth()[0] = new float[] {4, 8, -12};
        AvatarView.blend(start, end, 0.25f, mid);
        float[] blended = mid.cloth()[0];
        assert java.util.Arrays.equals(blended, new float[] {1, 2, -3}) : java.util.Arrays.toString(blended);
        AvatarView.blend(start, end, 1.5f, mid);
        assert mid.cloth()[0] == blended && blended[1] == 12 : "Extrapolated as the bones are, in the same array";
        end.cloth()[0] = null;
        AvatarView.blend(start, end, 0.25f, mid);
        assert mid.cloth()[0] == null : "One end without cloth";
        start.cloth()[0] = null;
        // Interpolation on publication times: bracketing samples, oldest clamp, bounded extrapolation, ring wrap.
        AvatarRecording.Pose[] ring = new AvatarRecording.Pose[4];
        double[] times = new double[4];
        for (int i = 0; i < 4; i++) {
            ring[i] = AvatarStream.emptyPose(single);
            ring[i].world()[0][3] = ring[i].local()[0][3] = 1;
        }
        int[] order = {2, 3, 0}; // Newest at index 0 after wrapping.
        double[] stamps = {0, 16, 32};
        for (int k = 0; k < 3; k++) { times[order[k]] = stamps[k]; ring[order[k]].world()[0][4] = (float) stamps[k]; }
        AvatarView.interpolate(ring, times, 0, 3, 8, mid);
        assert mid.world()[0][4] == 8 : mid.world()[0][4];
        AvatarView.interpolate(ring, times, 0, 3, 24, mid);
        assert mid.world()[0][4] == 24;
        AvatarView.interpolate(ring, times, 0, 3, 40, mid);
        assert mid.world()[0][4] == 40 : "Extrapolates up to half an interval";
        AvatarView.interpolate(ring, times, 0, 3, 90, mid);
        assert mid.world()[0][4] == 40 : "Extrapolation is bounded";
        AvatarView.interpolate(ring, times, 0, 3, -5, mid);
        assert mid.world()[0][4] == 0 : "Older than every sample shows the oldest";
        AvatarView.interpolate(ring, times, 0, 1, 50, mid);
        assert mid.world()[0][4] == 32 : "A single sample is shown as is";
        ring[0].cloth()[0] = new float[] {1, 2, 3};
        AvatarView.interpolate(ring, times, 0, 1, 50, mid);
        assert java.util.Arrays.equals(mid.cloth()[0], ring[0].cloth()[0]) && mid.cloth()[0] != ring[0].cloth()[0] : "With its cloth, copied";
        ring[0].cloth()[0] = null;
        AvatarView.interpolate(ring, times, 0, 1, 50, mid);
        assert mid.cloth()[0] == null : "And without";

        Path directory = Files.createTempDirectory("stellar-pose-");
        try {
            long now = System.currentTimeMillis();
            try (var stream = new AvatarStream(directory, RUN, now)) {
                assert Files.size(stream.path) == AvatarStream.TOTAL;
                assert Files.readString(directory.resolve("eve-pose-request.txt"))
                        .equals("SBMC_POSE_REQUEST 1\nRUN " + RUN + "\nTIME " + now + "\nSTATE active\nEND\n");
                Files.writeString(directory.resolve("eve-pose-components.1"), fixture);
                assert stream.table().generation() == 1;
                try (var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
                    assert map.getInt(96) != 0 && (int) RawFrameBus.INT.getAcquire(map, 256) == 1 && map.getInt(16) == 262144;
                    assert stream.poll(table, now, System.nanoTime()) == null : "Unclaimed: nothing yet";
                    stream.feedback(0.5f, 7, 30, 200);
                    for (int i = AvatarStream.HEALTH_AT; i < AvatarStream.GAINS_AT + 8; i++)
                        assert map.get(i) == 0 : "Native open needs Minecraft's words zero before the claim";
                    // The native claim, then one published slot.
                    assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
                    RawFrameBus.LONG.setRelease(map, 208, now);
                    RawFrameBus.INT.setRelease(map, 196, 1);
                    publish(map, 1, 1, 1, now, new int[] {7, 5}, payload());
                    map.putInt(AvatarStream.HEADER + AvatarStream.SLOT + 36, 1).putFloat(AvatarStream.HEADER + AvatarStream.SLOT + 44, 135);
                    var sample = stream.poll(table, now, System.nanoTime());
                    assert sample.counter() == 1 && sample.status()[0] == 7 && sample.payload()[4] == 100 && sample.rotation()[1] == 135
                            && !sample.lookApplied() && Float.isNaN(sample.cameraPitch());
                    assert (long) RawFrameBus.LONG.getAcquire(map, 272) == 1 : "Accepted counter is acknowledged";
                    assert stream.poll(table, now, System.nanoTime()) == null : "No new slot";
                    // Minecraft's camera goes to the adapter as one atomic word once the producer claimed the mapping.
                    stream.look(-15, 270, now);
                    assert map.getLong(288) == now && map.getInt(304) == 1 && map.getFloat(296) == -15 && map.getFloat(300) == 270;
                    // Its health and gains words beside the camera.
                    stream.feedback(0.5f, 7, 30, 200);
                    assert map.getFloat(280) == 0.5f && map.getInt(284) == 7 && map.getInt(312) == 30 && map.getInt(316) == 200;
                    stream.feedback(0.5f, 7, -1, 200);
                    assert map.getInt(312) == -1 && map.getInt(316) == 200 : "Wrapped totals keep their halves";
                    assert stream.poll(table, now, System.nanoTime()) == null : "Minecraft's words are not reserved bytes";
                    publish(map, 2, 2, 1, now, new int[] {7, 5}, payload());
                    // Bit 1: turned toward Minecraft's camera; bit 2: dolly set, with the camera's pitch at 52.
                    map.putInt(AvatarStream.HEADER + 2 * AvatarStream.SLOT + 36, 7).putFloat(AvatarStream.HEADER + 2 * AvatarStream.SLOT + 52, -17);
                    var synced = stream.poll(table, now, System.nanoTime());
                    assert synced.lookApplied() && synced.cameraPitch() == -17;
                    stream.lookOff();
                    assert map.getInt(304) == 0;

                    AvatarMesh mesh = AvatarMesh.parse("CH_P_EVE_52_TypeB", AvatarModelTest.mesh("Bip001", b -> { }));
                    var renamed = new AvatarRecording.Component("CharacterMesh0", "CH_P_EVE_52_TypeB", null,
                            new String[] {"Root", "Bip001"}, new String[0], 0);
                    var twoBones = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed), java.util.List.of(), java.util.List.of(), false, 30);
                    AvatarModel model = new AvatarModel(twoBones.components(), Map.of("CH_P_EVE_52_TypeB", mesh));
                    var pose = AvatarStream.emptyPose(twoBones);
                    float[] values = new float[30];
                    System.arraycopy(new float[] {0, 0, 0, 1, 100, 200, 300, 1, 1, 1}, 0, values, 0, 10);
                    float root90 = (float) Math.sqrt(0.5);
                    // Component space: root turned 90 degrees about X; the child's component transform already includes it.
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}, 0, values, 10, 10);
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, -100, 0, 1, 1, 1}, 0, values, 20, 10);
                    boolean[] shown = new boolean[1];
                    assert decode(twoBones, new AvatarStream.Sample(2, now, 1, new int[] {3}, values, null, false, Float.NaN, false, 0), model, pose, shown)
                            && shown[0];
                    FloatBuffer[] palettes = {FloatBuffer.allocate(24)};
                    model.palettes(pose, new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT), palettes, true);
                    AvatarModelTest.assertPoint(palettes[0], 1, 0, 0, 100, 1, 3, 1);
                    assert !decode(twoBones, new AvatarStream.Sample(3, now, 1, new int[] {1}, values, null, false, Float.NaN, false, 0), model, pose, shown)
                            : "The root without bones is incomplete";
                    // EVE's drone after her: without data or hidden in Stellar Blade it is only left out; a follower needs its leader's bones.
                    AvatarMesh drone = AvatarMesh.parse("CH_NPC_Drone", AvatarModelTest.mesh("BN_BODY", b -> { }));
                    var withDrone = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed,
                            new AvatarRecording.Component("Drone.CharacterMesh0", "CH_NPC_Drone", null, new String[] {"Root", "BN_BODY"}, new String[0], 0),
                            new AvatarRecording.Component("Drone.Gun", "CH_NPC_Drone", "Drone.CharacterMesh0", null, new String[0], 0)),
                            java.util.List.of(), java.util.List.of(), false, 70);
                    AvatarModel both = new AvatarModel(withDrone.components(), Map.of("CH_P_EVE_52_TypeB", mesh, "CH_NPC_Drone", drone));
                    var dronePose = AvatarStream.emptyPose(withDrone);
                    float[] doubled = new float[70];
                    System.arraycopy(values, 0, doubled, 0, 30);
                    System.arraycopy(values, 0, doubled, 30, 30);
                    System.arraycopy(values, 0, doubled, 60, 10);
                    boolean[] visible = new boolean[3];
                    assert decode(withDrone, new AvatarStream.Sample(4, now, 1, new int[] {3, 3, 1}, doubled, null, false, Float.NaN, false, 0), both, dronePose, visible)
                            && visible[0] && visible[1] && visible[2];
                    assert decode(withDrone, new AvatarStream.Sample(5, now, 1, new int[] {3, 3 | AvatarStream.HIDDEN, 1}, doubled, null, false, Float.NaN, false, 0),
                            both, dronePose, visible) && visible[0] && !visible[1] && visible[2] && dronePose.world()[1][4] == 100 : "Hidden: its floats still arrive";
                    assert decode(withDrone, new AvatarStream.Sample(6, now, 1, new int[] {3, 1, 1}, doubled, null, false, Float.NaN, false, 0), both, dronePose, visible)
                            && visible[0] && !visible[1] && !visible[2] : "No bones: the drone and its follower are left out, EVE is not";
                    assert decode(withDrone, new AvatarStream.Sample(7, now, 1, new int[] {3, 0, 0}, doubled, null, false, Float.NaN, false, 0), both, dronePose, visible)
                            && visible[0] && !visible[1] : "A destroyed drone";
                    // A drone whose skeleton differs from the pack (another skin) is left out of the model; EVE still draws.
                    var otherSkin = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed,
                            new AvatarRecording.Component("Drone.CharacterMesh0", "CH_NPC_Drone", null, new String[] {"Root", "BN_OTHER"}, new String[0], 0)),
                            java.util.List.of(), java.util.List.of(), false, 60);
                    AvatarModel skinned = new AvatarModel(otherSkin.components(), Map.of("CH_P_EVE_52_TypeB", mesh, "CH_NPC_Drone", drone));
                    assert skinned.parts.size() == 1 && skinned.skipped.get(0).startsWith("Drone.CharacterMesh0 (bones differ") : skinned.skipped;
                    // EVE's own parts are not optional: one without its bones still drops the pose.
                    var withWeapon = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed,
                            new AvatarRecording.Component("Weapon", "CH_NPC_Drone", null, new String[] {"Root", "BN_BODY"}, new String[0], 0)),
                            java.util.List.of(), java.util.List.of(), false, 60);
                    AvatarModel armed = new AvatarModel(withWeapon.components(), Map.of("CH_P_EVE_52_TypeB", mesh, "CH_NPC_Drone", drone));
                    assert !decode(withWeapon, new AvatarStream.Sample(8, now, 1, new int[] {3, 1}, java.util.Arrays.copyOf(doubled, 60), null, false, Float.NaN, false, 0),
                            armed, AvatarStream.emptyPose(withWeapon), new boolean[2]) : "A sword without bones drops the pose";
                    // Her drone's shots leave its bullet socket while a drone part is drawn, else her right hand.
                    var shooting = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(
                            new AvatarRecording.Component("CharacterMesh0", "CH_P_EVE_52_TypeB", null, new String[] {"Root", AvatarCombat.HAND}, new String[0], 0),
                            new AvatarRecording.Component("Drone.CharacterMesh0", "CH_NPC_Drone", null, new String[] {"Root", AvatarCombat.DRONE_MUZZLE}, new String[0], 0)),
                            java.util.List.of(), java.util.List.of(), false, 60);
                    AvatarModel shooter = new AvatarModel(shooting.components(), Map.of(
                            "CH_P_EVE_52_TypeB", AvatarMesh.parse("CH_P_EVE_52_TypeB", AvatarModelTest.mesh(AvatarCombat.HAND, b -> { })),
                            "CH_NPC_Drone", AvatarMesh.parse("CH_NPC_Drone", AvatarModelTest.mesh(AvatarCombat.DRONE_MUZZLE, b -> { }))));
                    var shooterPose = AvatarStream.emptyPose(shooting);
                    assert decode(shooting, new AvatarStream.Sample(9, now, 1, new int[] {3, 3}, java.util.Arrays.copyOf(doubled, 60), null, false, Float.NaN, false, 0),
                            shooter, shooterPose, new boolean[2]);
                    var placement = new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT);
                    shooter.palettes(shooterPose, placement, new FloatBuffer[] {FloatBuffer.allocate(24), FloatBuffer.allocate(24)}, true);
                    var camera = new net.minecraft.util.math.Vec3d(10, 20, 30);
                    // The socket (0.163, -11.05, 0.133) on the bone 100 units below the component, both turned 90 degrees about X.
                    var fromDrone = AvatarCombat.muzzle(shooter, shooting.components(), new boolean[] {true, true}, shooterPose, placement, camera);
                    assert fromDrone.from().equals("drone") && fromDrone.at().distanceTo(new net.minecraft.util.math.Vec3d(11.00163, 22.8895, 30.99867)) < 1e-4
                            : fromDrone;
                    var fromHand = AvatarCombat.muzzle(shooter, shooting.components(), new boolean[] {true, false}, shooterPose, placement, camera);
                    assert fromHand.from().equals("hand") && fromHand.at().distanceTo(new net.minecraft.util.math.Vec3d(11, 23, 31)) < 1e-4 : fromHand;
                    assert AvatarCombat.muzzle(model, twoBones.components(), new boolean[] {true}, pose, placement, camera) == null
                            : "Neither a drone nor her right hand: from her eyes";
                    // A part is drawn again only after two consecutive poses showed it.
                    boolean[] drawnNow = new boolean[2];
                    AvatarStream.steady(drawnNow, new boolean[] {true, false}, new boolean[] {true, true});
                    assert drawnNow[0] && !drawnNow[1] : "Hidden now";
                    AvatarStream.steady(drawnNow, new boolean[] {true, true}, new boolean[] {true, false});
                    assert !drawnNow[1] : "Shown once after hiding: not yet";
                    AvatarStream.steady(drawnNow, new boolean[] {true, true}, new boolean[] {true, true});
                    assert drawnNow[1] : "Shown twice: drawn";

                    // A slot of another generation asks the caller to reload the table.
                    publish(map, 0, 3, 2, now, new int[] {7, 5}, payload());
                    var newer = stream.poll(table, now, System.nanoTime());
                    assert newer.generation() == 2 && newer.payload() == null;
                    // Native statistics.
                    map.putInt(224, 40).putInt(228, 90);
                    RawFrameBus.LONG.setRelease(map, 216, 2L);
                    RawFrameBus.LONG.setRelease(map, 232, 120L);
                    long[] statistics = stream.statistics();
                    assert statistics[0] == 2 && statistics[1] == 40 && statistics[2] == 90 && statistics[3] == 60;
                    // EVE's gauges travel in the header once the adapter reads them.
                    assert stream.gauges() == null : "Unread gauges";
                    map.putFloat(324, 400).putFloat(328, 1000).putFloat(332, 130).putFloat(336, 1600).putFloat(340, 1);
                    RawFrameBus.INT.setRelease(map, 320, 1);
                    float[] gauges = stream.gauges();
                    assert gauges != null && gauges[0] == 400 && gauges[2] == 130 && gauges[3] == 1600 && gauges[4] == 1;
                    map.putFloat(332, 2000);
                    assert stream.gauges() == null : "A gauge above its maximum is not shown";
                    RawFrameBus.INT.setRelease(map, 320, 0);
                    // EVE's evade starts follow them.
                    assert java.util.Arrays.equals(stream.evades(), new int[8]) : "No evade counted yet";
                    map.putInt(344, 3).putInt(348, 3).putInt(352, 0).putInt(356, 3).putInt(360, 0).putInt(364, 3).putInt(368, 4)
                            .putInt(372, 7);
                    assert java.util.Arrays.equals(stream.evades(), new int[] {3, 3, 0, 3, 0, 3, 4, 7});
                    assert stream.poll(table, now, System.nanoTime()) == null : "Counted evades are not reserved bytes";
                    assert AvatarView.evadeStatus(stream.evades()).equals(" Evades: unlocked 3 first, 3 double, 0 cut; locked 3 first,"
                            + " 0 double, 3 cut; 4 evade commands, 7 lock evade rows changed by the lock.") : "The lock rows in the status";
                    // EVE's stats follow them once the adapter reads them.
                    assert stream.stats() == null : "Unread stats";
                    float[] stats = {2000, 2, 450, 5, 1.5f, 0, 0, -0.2f};
                    for (int i = 0; i < stats.length; i++) map.putFloat(AvatarStream.STATS_AT + 4 * i, stats[i]);
                    RawFrameBus.INT.setRelease(map, AvatarStream.STATS_STATE, 1);
                    assert java.util.Arrays.equals(stream.stats(), stats);
                    assert stream.poll(table, now, System.nanoTime()) == null : "Streamed stats are not reserved bytes";
                    map.putFloat(AvatarStream.STATS_AT + 8, 0);
                    assert stream.stats() == null : "No attack power is not EVE's";
                    map.putFloat(AvatarStream.STATS_AT + 8, 450).putFloat(AvatarStream.STATS_AT + 28, Float.NaN);
                    assert stream.stats() == null : "Nor is a non-finite rate";
                    RawFrameBus.INT.setRelease(map, AvatarStream.STATS_STATE, 0);
                    assert stream.stats() == null : "Unreadable again";
                    // Her drone's shots follow the stats: an unsigned count at 484.
                    assert AvatarStream.SHOTS_AT == 484 && stream.shots() == 0 : "No shot counted yet";
                    map.putInt(AvatarStream.SHOTS_AT, -2);
                    assert stream.shots() == 0xFFFFFFFEL;
                    assert stream.poll(table, now, System.nanoTime()) == null : "Counted shots are not reserved bytes";
                    // The adapter's report follows: heal count, mirror state, losses, Beta and Burst granted, actor stats.
                    assert AvatarStream.HEALED_AT == 488 && AvatarStream.MIRROR_AT == 492 && AvatarStream.LOST_AT == 496
                            && AvatarStream.GRANTED_AT == 500 && AvatarStream.ACTOR_STATE == 508 && AvatarStream.ACTOR_AT == 512
                            : "The native report layout";
                    map.putInt(AvatarStream.HEALED_AT, 1200).putInt(AvatarStream.MIRROR_AT, 2).putInt(AvatarStream.LOST_AT, 1)
                            .putInt(AvatarStream.GRANTED_AT, 17).putInt(AvatarStream.GRANTED_AT + 4, 200);
                    assert java.util.Arrays.equals(stream.report(), new long[] {1200, 2, 1, 17, 200}) && stream.healed() == 1200;
                    map.putInt(AvatarStream.HEALED_AT, -2);
                    assert stream.healed() == 0xFFFFFFFEL && stream.report()[0] == 0xFFFFFFFEL : "Unsigned counts";
                    map.putInt(AvatarStream.MIRROR_AT, 7);
                    assert stream.report()[1] == 0 : "An unknown mirror state reads off";
                    assert stream.actorStats() == null : "Unread actor stats";
                    for (int i = 0; i < AvatarStream.ACTOR_STATS; i++) map.putFloat(AvatarStream.ACTOR_AT + 4 * i, i / 2f);
                    RawFrameBus.INT.setRelease(map, AvatarStream.ACTOR_STATE, 1);
                    float[] actorStats = stream.actorStats();
                    assert actorStats.length == 131 && actorStats[0] == 0 && actorStats[130] == 65f;
                    assert stream.poll(table, now, System.nanoTime()) == null : "The adapter's report is not reserved bytes";
                    // Native release, then reaping deletes the mapping and the request says stopped.
                    RawFrameBus.INT.setRelease(map, 196, 2);
                    try { stream.poll(table, now, System.nanoTime()); throw new AssertionError("A stopped producer must end the stream"); }
                    catch (java.io.IOException expected) { assert expected.getMessage().contains("stopped"); }
                    assert Files.readString(directory.resolve("eve-pose-request.txt")).contains("STATE stopped");
                    assert stream.reap() && !Files.exists(stream.path);
                }
            }
            // A slot of the previous format (an older adapter) ends the stream with the reason.
            assert refusedWith(directory, table, AvatarStream.HEADER + AvatarStream.SLOT + 24, 4)
                    .equals("Pose slot format 4, this build reads 5: install the matching adapter");
            cloth(directory, fixture);
            // The header stays reserved between the camera and the gains word, and after the adapter's report.
            assert refusedWith(directory, table, 308, 1).equals("Pose mapping reserved bytes changed");
            assert refusedWith(directory, table, 376, 1).equals("Pose mapping reserved bytes changed") : "Reserved after the evade words";
            assert AvatarStream.ACTOR_END == 1036 && refusedWith(directory, table, AvatarStream.ACTOR_END, 1).equals("Pose mapping reserved bytes changed");
            // A refused slot ends the stream: bit 2 without bit 0, an unknown bit, a camera pitch without bit 2, a non-finite one.
            assert refused(directory, table, 4, 0).equals("Invalid controller rotation status");
            assert refused(directory, table, 17, 0).equals("Invalid controller rotation status");
            assert refused(directory, table, 1, -17).equals("Pose mapping reserved bytes changed");
            assert refused(directory, table, 5, Float.NaN).equals("Non-finite camera pitch");
            // Each bit decodes alone: 5 is the dolly set while the yaw follow stopped, 3 a turn without the dolly.
            var held = slot(directory, table, 5, -17);
            assert !held.lookApplied() && held.cameraPitch() == -17;
            var turned = slot(directory, table, 3, 0);
            assert turned.lookApplied() && Float.isNaN(turned.cameraPitch()) && !turned.inputGranted();
            var aiming = slot(directory, table, 1, 0, 7);
            assert aiming.dollyRefusal() == 7 && AvatarStream.DOLLY_REFUSALS[6].equals("aim") : "Why the dolly was left alone";
            assert refused(directory, table, 5, -17, 7).equals("Invalid dolly refusal") : "No refusal on a synced frame";
            assert refused(directory, table, 1, 0, 11).equals("Invalid dolly refusal");
            var granted = slot(directory, table, 9, 0);
            assert granted.inputGranted() && !granted.lookApplied() && Float.isNaN(granted.cameraPitch()) : "Bit 3: this run owns forwarded input";
            // A stream the producer never claimed is cancelled and removed.
            try (var unclaimed = new AvatarStream(directory, "d".repeat(32), System.currentTimeMillis())) {
                assert unclaimed.reap() && !Files.exists(unclaimed.path);
            }
            // keepAlive keeps the request and the lease fresh while the render thread is blocked (an avatar rebuild) past
            // Lua's 3 s lease, and never writes after the stop.
            Path request = directory.resolve("eve-pose-request.txt");
            try (var kept = new AvatarStream(directory, "e".repeat(32), System.currentTimeMillis());
                 var channel = FileChannel.open(kept.path, StandardOpenOption.READ)) {
                var map = channel.map(FileChannel.MapMode.READ_ONLY, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
                kept.keepAlive();
                Thread.sleep(3500); // The blocked render thread: no heartbeat.
                long at = System.currentTimeMillis();
                String fresh = Files.readString(request);
                long time = Long.parseLong(fresh.split("\n")[2].substring("TIME ".length()));
                assert fresh.equals("SBMC_POSE_REQUEST 1\nRUN " + "e".repeat(32) + "\nTIME " + time + "\nSTATE active\nEND\n")
                        && at - time <= 1000 + 2 * AvatarStream.KEEP_ALIVE_MS : fresh;
                assert at - (long) RawFrameBus.LONG.getAcquire(map, 264) <= 2 * AvatarStream.KEEP_ALIVE_MS : "The consumer lease stays fresh";
                kept.stop();
                String stopped = Files.readString(request);
                Thread.sleep(3 * AvatarStream.KEEP_ALIVE_MS);
                assert stopped.contains("\nSTATE stopped\n") && Files.readString(request).equals(stopped) : "A stopped request stays stopped";
            }
        } finally {
            try (var files = Files.walk(directory)) { files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete()); }
        }
        rebuilds(fixture);
        System.out.println("PASS avatar stream: shared table grammar, newest run slot, native mapping protocol, decoding, component-space skinning, release, steering, display interpolation, combat, gauge gains, heals, the HUD panel and rebuilds off the render thread");
    }

    /**
     * AvatarRebuild: a build superseded while it runs is discarded, take hands over only the wanted table with the result built
     * from that table, a table no longer wanted (the drawn model fits the newest) leaves nothing, and a failure comes with its table.
     */
    private static void rebuilds(String fixture) throws Exception {
        java.util.function.LongFunction<AvatarStream.Table> generation =
                g -> AvatarStream.parse(fixture.replace("GENERATION 1", "GENERATION " + g).replace("END 1", "END " + g));
        var building = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var building7 = new java.util.concurrent.CountDownLatch(1);
        var release7 = new java.util.concurrent.CountDownLatch(1);
        var discarded = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        try (var rebuild = new AvatarRebuild<String>(table -> {
            if (table.generation() == 5) { building.countDown(); release.await(); } // A slow build: the suit's textures.
            if (table.generation() == 7) { building7.countDown(); release7.await(); }
            if (table.generation() == 8) throw new java.io.IOException("No streamed EVE mesh");
            return "model of generation " + table.generation();
        }, discarded::add)) {
            rebuild.want(generation.apply(5));
            building.await();
            rebuild.want(generation.apply(5)); // The same generation read again: no second build.
            var six = generation.apply(6);
            rebuild.want(six);
            assert rebuild.take() == null : "Nothing while building";
            release.countDown();
            var built = awaitBuild(rebuild);
            assert built.table() == six && built.result().equals("model of generation 6") && built.failure() == null;
            assert discarded.equals(java.util.List.of("model of generation 5")) : "The superseded build is discarded: " + discarded;
            assert rebuild.take() == null : "Handed over once";
            rebuild.want(generation.apply(7));
            building7.await();
            rebuild.want(null); // The drawn model fits the newest table.
            release7.countDown();
            for (int i = 0; i < 500 && discarded.size() < 2; i++) Thread.sleep(10);
            assert rebuild.take() == null && discarded.get(1).equals("model of generation 7");
            var eight = generation.apply(8);
            rebuild.want(eight);
            built = awaitBuild(rebuild);
            assert built.table() == eight && built.result() == null && built.failure().getMessage().equals("No streamed EVE mesh");
        }
        // Tables arriving faster than builds finish: whatever take hands over is the wanted table and the result built from it.
        var random = new java.util.Random(7);
        try (var rebuild = new AvatarRebuild<String>(table -> {
            Thread.sleep(random.nextInt(3));
            return "model of generation " + table.generation();
        }, discarded::add)) {
            int taken = 0;
            for (long g = 10; g < 400; g++) {
                rebuild.want(g % 17 == 0 ? null : generation.apply(g));
                Thread.sleep(random.nextInt(2));
                var built = rebuild.take();
                if (built == null) continue;
                taken++;
                assert built.table() == rebuild.wanted() && built.result().equals("model of generation " + built.table().generation()) : built;
            }
            assert taken > 0 : "Some builds finish between tables";
        }
    }
    private static <T> AvatarRebuild.Built<T> awaitBuild(AvatarRebuild<T> rebuild) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            var built = rebuild.take();
            if (built != null) return built;
            Thread.sleep(10);
        }
        throw new AssertionError("No build within 5 s");
    }

    /** Why a fresh stream (its run named by status) refuses one native slot with this controller status and camera pitch. */
    private static String refused(Path directory, AvatarStream.Table table, int status, float pitch) throws Exception {
        return refused(directory, table, status, pitch, 0);
    }
    private static String refused(Path directory, AvatarStream.Table table, int status, float pitch, int refusal) throws Exception {
        try {
            slot(directory, table, status, pitch, refusal);
            throw new AssertionError("Accepted controller status " + status);
        } catch (java.io.IOException failure) {
            return failure.getMessage();
        }
    }

    /** Why a fresh stream refuses its first native slot once value is written at offset of the mapping. */
    private static String refusedWith(Path directory, AvatarStream.Table table, int offset, int value) throws Exception {
        long now = System.currentTimeMillis();
        try (var stream = new AvatarStream(directory, String.format("f%031x", offset), now);
             var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
            assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
            RawFrameBus.LONG.setRelease(map, 208, now);
            RawFrameBus.INT.setRelease(map, 196, 1);
            publish(map, 1, 1, 1, now, new int[] {7, 5}, payload());
            map.putInt(offset, value);
            stream.poll(table, now, System.nanoTime());
            throw new AssertionError("Accepted " + value + " at " + offset);
        } catch (java.io.IOException refusal) {
            return refusal.getMessage();
        }
    }

    /** What a fresh stream (its run named by status and pitch) decodes from one native slot with this controller status and camera pitch. */
    private static AvatarStream.Sample slot(Path directory, AvatarStream.Table table, int status, float pitch) throws Exception {
        return slot(directory, table, status, pitch, 0);
    }
    private static AvatarStream.Sample slot(Path directory, AvatarStream.Table table, int status, float pitch, int refusal) throws Exception {
        long now = System.currentTimeMillis();
        try (var stream = new AvatarStream(directory, String.format("%020x%04x%08x", status, refusal, Float.floatToRawIntBits(pitch)), now);
             var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
            assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
            RawFrameBus.LONG.setRelease(map, 208, now);
            RawFrameBus.INT.setRelease(map, 196, 1);
            publish(map, 1, 1, 1, now, new int[] {7, 5}, payload());
            map.putInt(AvatarStream.HEADER + AvatarStream.SLOT + 36, status).putFloat(AvatarStream.HEADER + AvatarStream.SLOT + 52, pitch)
                    .putInt(AvatarStream.HEADER + AvatarStream.SLOT + 56, refusal);
            return stream.poll(table, now, System.nanoTime());
        }
    }

    /**
     * Stellar Blade's cloth in format 5: its particles after the table's floats, their count at slot byte 60 and the CLOTH bit
     * on the table's cloth component only; decoded into component space for a part with as many vertices, else bones; and the
     * watch that drops a cloth frozen while her head moves.
     */
    private static void cloth(Path directory, String fixture) throws Exception {
        var worn = AvatarStream.parse(fixture.replace("CLOTH none", "CLOTH 2"));
        float turn = (float) Math.sqrt(0.5);
        // ComponentRelativeTransform -90 degrees about Y (the Root bone's), moved by (1, 2, 3); particles (10, 0, 0) and (0, 0, 10).
        float[] floats = {0, -turn, 0, turn, 1, 2, 3, 1, 1, 1, 10, 0, 0, 0, 0, 10};
        float[] payload = java.util.Arrays.copyOf(payload(), 52 + floats.length);
        System.arraycopy(floats, 0, payload, 52, floats.length);
        var sample = clothSlot(directory, worn, new int[] {7, 5 | AvatarStream.CLOTH}, 2, payload);
        assert sample.payload().length == 52 && java.util.Arrays.equals(sample.cloth(), floats) : "The table's floats, then the cloth";
        assert clothSlot(directory, worn, new int[] {7, 5}, 0, payload()).cloth() == null : "No particles, no cloth";
        assert clothRefused(directory, worn, new int[] {7 | AvatarStream.CLOTH, 5}, 2, payload).equals("Cloth on a component the table does not name");
        assert clothRefused(directory, table(fixture), new int[] {7, 5 | AvatarStream.CLOTH}, 2, payload).equals("Cloth on a component the table does not name")
                : "A table without cloth";
        assert clothRefused(directory, worn, new int[] {7, 5}, 2, payload).equals("Cloth particles without their component");
        assert clothRefused(directory, worn, new int[] {7, 5 | AvatarStream.CLOTH}, 0, payload()).equals("Cloth on a component the table does not name")
                : "The bit without particles";
        assert clothRefused(directory, worn, new int[] {7, 4 | AvatarStream.CLOTH}, 2, payload).equals("Cloth on a component the table does not name")
                : "Cloth of a component not read";
        int past = (AvatarStream.CAPACITY - 52 - 10) / 3 + 1;
        assert clothRefused(directory, worn, new int[] {7, 5 | AvatarStream.CLOTH}, past, java.util.Arrays.copyOf(payload, 52 + 10 + 3 * past))
                .equals("Invalid cloth particle count") : "Past the slot";
        payload[60] = Float.NaN;
        assert clothRefused(directory, worn, new int[] {7, 5 | AvatarStream.CLOTH}, 2, payload).equals("Non-finite cloth value");

        // Decoded for EVE's ponytail mesh, two vertices here: R_y(-90) (x, y, z) = (-z, y, x), then moved.
        AvatarMesh body = AvatarMesh.parse("Body", AvatarModelTest.mesh("Bip001-Head", b -> { })), tail = AvatarMesh.parse("Tail", AvatarModelTest.mesh("Tip", b -> { }));
        var hair = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(
                new AvatarRecording.Component("CharacterMesh0", "Body", null, new String[] {"Root", "Bip001-Head"}, new String[0], 0),
                new AvatarRecording.Component("SBPonytail", "Tail", "CharacterMesh0", null, new String[0], 0)),
                java.util.List.of(), java.util.List.of(), false, 40, 1);
        AvatarModel model = new AvatarModel(hair.components(), Map.of("Body", body, "Tail", tail));
        var pose = AvatarStream.emptyPose(hair);
        float[] values = new float[40];
        for (int at : new int[] {0, 10, 20, 30}) values[at + 3] = values[at + 7] = values[at + 8] = values[at + 9] = 1;
        java.util.function.Function<float[], AvatarStream.Sample> worn2 = cloth -> new AvatarStream.Sample(1, 1, 1, new int[] {3, 1}, values.clone(),
                null, false, Float.NaN, false, 0, cloth);
        assert decode(hair, worn2.apply(floats.clone()), model, pose, new boolean[2]) && pose.cloth()[0] == null;
        float[] decoded = pose.cloth()[1];
        float[] want = {1, 2, 13, -9, 2, 3};
        for (int k = 0; k < 6; k++) assert Math.abs(decoded[k] - want[k]) < 1e-5 : java.util.Arrays.toString(decoded);
        decode(hair, worn2.apply(floats.clone()), model, pose, new boolean[2]);
        assert pose.cloth()[1] == decoded : "The array is reused";
        assert decode(hair, worn2.apply(java.util.Arrays.copyOf(floats, 19)), model, pose, new boolean[2]) && pose.cloth()[1] == null
                : "Another particle count than the mesh's vertices: bones";
        float[] skewed = floats.clone();
        skewed[3] = 0.9f;
        assert decode(hair, worn2.apply(skewed), model, pose, new boolean[2]) && pose.cloth()[1] == null : "No rotation";
        skewed = floats.clone();
        skewed[8] = 0;
        assert decode(hair, worn2.apply(skewed), model, pose, new boolean[2]) && pose.cloth()[1] == null : "A flat scale";
        assert decode(hair, worn2.apply(null), model, pose, new boolean[2]) && pose.cloth()[1] == null : "No cloth";
        var bare = new AvatarModel(hair.components(), Map.of("Body", body));
        assert decode(hair, worn2.apply(floats.clone()), bare, pose, new boolean[2]) && pose.cloth()[1] == null : "No ponytail mesh";

        // The watch: the same particles while her head (the leader's Bip001-Head at 20) moves are stale; STALE in a row drop
        // the cloth until it moves again. A still head or moving particles are live.
        var watch = quiet(new AvatarStream.ClothWatch());
        java.util.function.BiFunction<Float, Float, AvatarStream.Sample> at = (head, particle) -> {
            float[] p = values.clone(), c = floats.clone();
            p[24] = head;
            c[10] = particle;
            return new AvatarStream.Sample(1, 1, 1, new int[] {3, 1}, p, null, false, Float.NaN, false, 0, c);
        };
        assert accept(watch, hair, at.apply(0f, 0f)) && watch.stale == 0;
        for (int k = 1; k < AvatarStream.ClothWatch.STALE; k++) assert accept(watch, hair, at.apply((float) k, 0f)) : "Stale " + k + " times: kept";
        assert !accept(watch, hair, at.apply(99f, 0f)) && watch.stale == AvatarStream.ClothWatch.STALE : "Frozen while the head moves: bones";
        assert !accept(watch, hair, at.apply(100f, 0f)) && !accept(watch, hair, at.apply(100f, 0f)) && !accept(watch, hair, at.apply(100f, 0f))
                : "A still head (a pause, a hit-stop) keeps a frozen cloth on bones";
        assert accept(watch, hair, at.apply(101f, 1f)) : "Until the particles move";
        var decoding = quiet(new AvatarStream.ClothWatch());
        for (int k = 0; k < AvatarStream.ClothWatch.STALE; k++)
            assert AvatarStream.decode(hair, at.apply((float) k, 0f), model, pose, new boolean[2], decoding) && pose.cloth()[1] != null;
        assert AvatarStream.decode(hair, at.apply(99f, 0f), model, pose, new boolean[2], decoding) && pose.cloth()[1] == null : "Decoded frozen: bones";
        assert accept(watch, hair, at.apply(101f, 1f)) && accept(watch, hair, at.apply(101f, 1f)) && watch.stale == AvatarStream.ClothWatch.STALE + 1
                : "A still head with still particles is not stale";
        assert accept(watch, hair, at.apply(102f, 1f)) && watch.stale == AvatarStream.ClothWatch.STALE + 2 : "Counted";
        var moving = values.clone();
        assert !accept(watch, hair, new AvatarStream.Sample(1, 1, 1, new int[] {3, 1}, moving, null, false, Float.NaN, false, 0, null))
                && accept(watch, hair, at.apply(103f, 1f)) && watch.stale == AvatarStream.ClothWatch.STALE + 2 : "A sample without cloth restarts the watch";
        clothDiagnostics(hair, bare, values, floats);
    }
    /**
     * The watch's diagnostics: Stellar Blade's cloth cadence, stale runs ended by moving particles, each switch to bones by
     * reason with its log line and the way back, jumps after unchanged particles against jumps while they moved, a model
     * rebuild, the status and the log budget.
     */
    private static void clothDiagnostics(AvatarStream.Table hair, AvatarModel bare, float[] values, float[] floats) {
        var watch = new AvatarStream.ClothWatch();
        var lines = new java.util.ArrayList<String>();
        watch.log = lines::add;
        long t = 1000;
        float head = 0, x = 0;
        assert accept(watch, hair, worn(values, floats, t, head, x)) && lines.equals(java.util.List.of("Cloth: Stellar Blade's cloth drawn from sample 1000"));
        // Stale runs of 1, 2, 5, 6, 29 and 30 samples, each ended by a 1 cm move: the last three froze the cloth for a while.
        for (int length : new int[] {1, 2, 5, 6, 29, 30}) {
            for (int k = 0; k < length; k++) accept(watch, hair, worn(values, floats, t += 16, ++head, x));
            assert accept(watch, hair, worn(values, floats, t += 16, ++head, ++x)) : "Moving particles end a run of " + length;
        }
        assert java.util.Arrays.equals(watch.runs, new long[] {1, 2, 2, 1}) && watch.longest == 30 : java.util.Arrays.toString(watch.runs);
        assert watch.stale == 73 && watch.compared == 79 && watch.changed == 6 && watch.headMoved == 79;
        assert watch.drops[AvatarStream.ClothWatch.FROZEN] == 3 && watch.bones[AvatarStream.ClothWatch.FROZEN] == 1 + 24 + 25
                : "From the sixth stale sample of each run until the particles move";
        assert lines.size() == 7 && lines.get(1).endsWith(", frozen (particles unchanged for 96 ms over 6 samples while her head moved)")
                && lines.get(2).endsWith(" after 1 samples (16 ms) on bones; its measured particles moved " + String.format("%.1f cm", 1f))
                && lines.get(4).endsWith(" after 24 samples (384 ms) on bones; its measured particles moved " + String.format("%.1f cm", 1f)) : lines;
        assert accept(watch, hair, worn(values, floats, t += 16, head, ++x)) && watch.compared == 80 && watch.headMoved == 79 && watch.stale == 73
                : "Her head still, the cloth moving";
        assert watch.status().contains(" Bones instead: 3 frozen (50 samples), 0 absent (0),")
                && watch.status().contains(" Particles changed in 9% of cloth samples, her head in 99%. Stale runs ended by moving particles:"
                + " 1 of 1, 2 of 2-5, 2 of 6-29, 1 of 30+; longest 30.") : watch.status();
        // Jumps between consecutive cloth samples: moves over 30 cm while the particles moved, or after unchanged ones.
        lines.clear();
        accept(watch, hair, worn(values, floats, t += 16, ++head, x += 40));
        accept(watch, hair, worn(values, floats, t += 16, ++head, x += 29));
        accept(watch, hair, worn(values, floats, t += 16, ++head, x += 31));
        assert watch.jumpsMoving == 2 && Math.abs(watch.largestMoving - 40) < 1e-3 && watch.jumpsStill == 0 && lines.size() == 2
                && lines.get(0).endsWith(" while the particles moved") : lines;
        accept(watch, hair, worn(values, floats, t += 16, ++head, x));
        accept(watch, hair, worn(values, floats, t += 16, ++head, x += 50));
        assert watch.jumpsStill == 1 && Math.abs(watch.largestStill - 50) < 1e-3 && lines.get(2).endsWith(" after 1 unchanged samples (32 ms)") : lines;
        accept(watch, hair, worn(values, floats, t += 16, head, x));
        accept(watch, hair, worn(values, floats, t += 16, ++head, x += 45));
        assert watch.jumpsStill == 2 && watch.jumpsMoving == 2 && Math.abs(watch.largestStill - 50) < 1e-3 : "Unchanged with her head still counts too";
        // Each reason for bones, and back: absent, mismatched, invalid, no ponytail mesh, a model rebuild.
        lines.clear();
        assert !accept(watch, hair, new AvatarStream.Sample(t += 16, t, 1, new int[] {3, 1}, values.clone(), null, false, Float.NaN, false, 0, null));
        assert accept(watch, hair, worn(values, floats, t += 16, ++head, x += 60)) && watch.jumpsMoving == 2 : "Across a gap: no jump";
        assert lines.get(0).equals("Cloth: bones from sample " + (t - 16) + ", absent (no cloth in the sample; component status 0x1)")
                && lines.get(1).endsWith(" after 1 samples (16 ms) on bones; its measured particles moved " + String.format("%.1f cm", 60f)) : lines;
        assert !accept(watch, hair, worn(values, java.util.Arrays.copyOf(floats, 19), t += 16, ++head, x)) && accept(watch, hair, worn(values, floats, t += 16, ++head, ++x));
        float[] skewed = floats.clone();
        skewed[3] = 0.9f;
        assert !accept(watch, hair, worn(values, skewed, t += 16, ++head, x)) && accept(watch, hair, worn(values, floats, t += 16, ++head, ++x));
        var pose = AvatarStream.emptyPose(hair);
        assert AvatarStream.decode(hair, worn(values, floats, t += 16, ++head, ++x), bare, pose, new boolean[2], watch) && pose.cloth()[1] == null;
        assert accept(watch, hair, worn(values, floats, t += 16, ++head, ++x));
        assert lines.get(2).endsWith(", mismatched (3 particles, the ponytail mesh has 2 vertices)")
                && lines.get(4).endsWith(", invalid (its transform has no unit rotation or a non-positive scale)")
                && lines.get(6).endsWith(", mismatched (no ponytail mesh in the pack)") && lines.size() == 8 : lines;
        watch.rebuild(7);
        watch.rebuild(8);
        assert accept(watch, hair, worn(values, floats, t += 16, ++head, x)) && watch.drops[AvatarStream.ClothWatch.REBUILT] == 1;
        assert lines.get(8).equals("Cloth: bones at the model rebuild for generation 7") && lines.size() == 10
                && lines.get(9).endsWith(" after 0 samples (16 ms) on bones; its measured particles moved " + String.format("%.1f cm", 0f)) : lines;
        assert watch.status().contains(" 1 absent (1), 2 mismatched (2), 1 invalid (1), 1 at a model rebuild.") && watch.status().contains(String.format(
                " Jumps over 30 cm: 2 after unchanged particles (largest %.1f cm), 2 while they moved (largest %.1f cm).", 50f, 40f)) : watch.status();
        // At most LINES switch and jump lines per stream run, then one notice; the counters go on.
        var flapping = new AvatarStream.ClothWatch();
        var flapped = new java.util.ArrayList<String>();
        flapping.log = flapped::add;
        for (int k = 0; k < AvatarStream.ClothWatch.LINES; k++) {
            accept(flapping, hair, worn(values, floats, 2 * k, k, 0));
            accept(flapping, hair, new AvatarStream.Sample(2 * k + 1, 2 * k + 1, 1, new int[] {3, 1}, values.clone(), null, false, Float.NaN, false, 0, null));
        }
        assert flapped.size() == AvatarStream.ClothWatch.LINES + 1 && flapped.getLast().equals("Cloth: further cloth switches and jumps not logged.")
                && flapped.get(AvatarStream.ClothWatch.LINES - 1).startsWith("Cloth: bones from sample ")
                && flapping.drops[AvatarStream.ClothWatch.ABSENT] == AvatarStream.ClothWatch.LINES : flapped.getLast();
    }
    private static AvatarStream.ClothWatch quiet(AvatarStream.ClothWatch watch) {
        watch.log = line -> { };
        return watch;
    }
    /** The watch on a sample for the test's two-vertex ponytail. */
    private static boolean accept(AvatarStream.ClothWatch watch, AvatarStream.Table table, AvatarStream.Sample sample) {
        return watch.accept(table, sample, 2, AvatarStream.cloth(sample.cloth(), 2, null));
    }
    /** A cloth sample published at ms (its counter too) with her head's x (the leader's Bip001-Head) and particle 0's x. */
    private static AvatarStream.Sample worn(float[] values, float[] floats, long ms, float head, float particle) {
        float[] p = values.clone(), c = floats.clone();
        p[24] = head;
        c[10] = particle;
        return new AvatarStream.Sample(ms, ms, 1, new int[] {3, 1}, p, null, false, Float.NaN, false, 0, c);
    }
    /** Decodes with a fresh cloth watch: one sample is never frozen. */
    private static boolean decode(AvatarStream.Table table, AvatarStream.Sample sample, AvatarModel model, AvatarRecording.Pose target, boolean[] shown) {
        return AvatarStream.decode(table, sample, model, target, shown, quiet(new AvatarStream.ClothWatch()));
    }
    private static AvatarStream.Table table(String fixture) { return AvatarStream.parse(fixture); }
    private static int clothRuns; // Each cloth slot's stream has its own run.
    /** What a fresh stream decodes from one native slot with these statuses, cloth particles and payload. */
    private static AvatarStream.Sample clothSlot(Path directory, AvatarStream.Table table, int[] status, int particles, float[] payload) throws Exception {
        long now = System.currentTimeMillis();
        try (var stream = new AvatarStream(directory, String.format("c%031x", ++clothRuns), now);
             var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
            assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
            RawFrameBus.LONG.setRelease(map, 208, now);
            RawFrameBus.INT.setRelease(map, 196, 1);
            publish(map, 1, 1, 1, now, status, payload, particles);
            return stream.poll(table, now, System.nanoTime());
        }
    }
    private static String clothRefused(Path directory, AvatarStream.Table table, int[] status, int particles, float[] payload) throws Exception {
        try {
            clothSlot(directory, table, status, particles, payload);
            throw new AssertionError("Accepted cloth " + particles);
        } catch (java.io.IOException failure) {
            return failure.getMessage();
        }
    }

    /** Reads poses from the external native fixture (pose_stream_fixture), which plays Lua and the game thread. */
    private static void interop(Path directory, int wanted) throws Exception {
        Files.createDirectories(directory);
        String run = "e".repeat(32);
        try (var stream = new AvatarStream(directory, run, System.currentTimeMillis())) {
            System.out.println("READY " + directory + " " + run + " " + wanted);
            long until = System.nanoTime() + 30_000_000_000L, beat = 0;
            AvatarStream.Table table = null;
            int decoded = 0;
            float lastBone = 0;
            while (decoded < wanted) {
                if (System.nanoTime() >= until) throw new AssertionError("Native pose producer deadline");
                if (System.nanoTime() - beat > 500_000_000L) { stream.heartbeat(System.currentTimeMillis()); beat = System.nanoTime(); }
                var sample = stream.poll(table, System.currentTimeMillis(), System.nanoTime());
                if (sample == null) { Thread.sleep(1); continue; }
                if (sample.payload() == null) { table = stream.table(); continue; }
                assert table.components().size() == 2 && sample.status()[0] == (AvatarStream.WORLD | AvatarStream.BONES)
                        && sample.status()[1] == (AvatarStream.WORLD | AvatarStream.MORPHS) : java.util.Arrays.toString(sample.status());
                float[] p = sample.payload();
                assert p[4] == 100 && p[5] == 200 && p[6] == 300 && p[13] == 1 && p[10 + 10 + 6] == 1 : "World and bone transforms survive";
                float bone = p[10 + 10 + 4];
                assert bone > lastBone && (p[51] == 0 || p[51] == 1) : "Poses arrive in order";
                lastBone = bone;
                decoded++;
            }
            stream.stop();
            long release = System.nanoTime() + 10_000_000_000L;
            while (!stream.reap()) {
                if (System.nanoTime() > release) throw new AssertionError("Native producer did not release the run");
                Thread.sleep(10);
            }
            System.out.println("PASS pose interop: " + decoded + " ordered native poses read, run released and deleted");
        }
    }

    /** CharacterMesh0 world + 3 bones, then Mesh_Face world + 2 morph weights. */
    static float[] payload() {
        float[] values = new float[52];
        System.arraycopy(new float[] {0, 0, 0, 1, 100, 200, 300, 1, 1, 1}, 0, values, 0, 10);
        for (int k = 0; k < 3; k++) values[10 + 10 * k + 3] = 1;
        values[40 + 3] = 1;
        values[50] = 0.25f;
        values[51] = 1;
        return values;
    }

    /** Writes a native slot into back and exchanges it into the middle, as the adapter does. */
    static void publish(java.nio.ByteBuffer map, int back, long counter, long generation, long now, int[] status, float[] payload) {
        publish(map, back, counter, generation, now, status, payload, 0);
    }
    /** The same with cloth particles: payload ends with their 10 + 3 * particles floats. */
    static void publish(java.nio.ByteBuffer map, int back, long counter, long generation, long now, int[] status, float[] payload, int particles) {
        int at = AvatarStream.HEADER + back * AvatarStream.SLOT;
        for (int i = 0; i < AvatarStream.SLOT_HEADER; i++) map.put(at + i, (byte) 0);
        map.putLong(at, counter).putLong(at + 8, now).putLong(at + 16, generation).putInt(at + 24, AvatarStream.FORMAT)
                .putInt(at + 28, status.length).putInt(at + 32, payload.length - (particles > 0 ? 10 + 3 * particles : 0))
                .putInt(at + 60, particles);
        for (int i = 0; i < status.length; i++) map.putInt(at + 64 + 4 * i, status[i]);
        for (int i = 0; i < payload.length; i++) map.putFloat(at + AvatarStream.SLOT_HEADER + 4 * i, payload[i]);
        RawFrameBus.INT.getAndSet(map, 128, back | 4);
    }
}

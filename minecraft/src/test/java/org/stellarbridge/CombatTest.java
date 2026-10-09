package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Combat per player on the server: each Fighter judges its own presses and remembers its own verdicts, mines are
 * capped per owner, strikes pass reach, rate and rehit checks, presses over the network count when the player made them
 * within the allowance, a multiplayer silence has its grace, and no player's damage reaches an EVE player.
 */
public final class CombatTest {
    private static final long MS = 1_000_000L;

    public static void main(String[] args) throws Exception {
        long impact = 1_000_000 * MS;

        // A creeper's blast hits A and B in the same tick: A's perfect dodge is hers alone, and B never inherits it.
        var a = new AvatarCombat.Fighter();
        var b = new AvatarCombat.Fighter();
        a.press(BridgeNet.Defense.DODGE, impact - 100 * MS);
        assert a.judge(impact, false) == AvatarCombat.Defense.PERFECT_DODGE && b.judge(impact, false) == AvatarCombat.Defense.HIT;
        int creeper = 77, tick = 4000;
        a.judged(creeper, tick, AvatarCombat.Defense.PERFECT_DODGE);
        assert a.repeats(creeper, tick + 1) && !b.repeats(creeper, tick + 1) : "One player's verdict never spares another";
        b.judged(creeper, tick, AvatarCombat.Defense.HIT);
        assert a.repeats(creeper, tick + AvatarCombat.REPEAT - 1) && !a.repeats(creeper, tick + AvatarCombat.REPEAT) && !a.repeats(78, tick + 1);
        assert a.judge(impact - 101 * MS, false) == AvatarCombat.Defense.HIT : "A press after the impact never counts";

        // The guard: a tap parries, a held guard guards, mashing is refused by the cooldown, a release ends the hold, and a
        // guard held longer than the presses are kept stays held.
        var guard = new AvatarCombat.Fighter();
        guard.press(BridgeNet.Defense.GUARD_DOWN, impact - 300 * MS);
        assert guard.judge(impact, false) == AvatarCombat.Defense.PARRY && guard.judge(impact, true) == AvatarCombat.Defense.HIT
                : "A tapped guard parries; unblockable damage ignores it";
        guard.press(BridgeNet.Defense.GUARD_UP, impact - 250 * MS);
        guard.press(BridgeNet.Defense.GUARD_DOWN, impact - 100 * MS); // 200 ms after the last press: inside the cooldown.
        assert guard.judge(impact, false) == AvatarCombat.Defense.PARRY : "The mashed press does not count; the first one still parries";
        assert guard.judge(impact + 200 * MS, false) == AvatarCombat.Defense.GUARD : "Held, past the parry windows";
        guard.press(BridgeNet.Defense.GUARD_UP, impact + 300 * MS);
        assert guard.judge(impact + 400 * MS, false) == AvatarCombat.Defense.HIT : "Released";
        guard.press(BridgeNet.Defense.GUARD_DOWN, impact + 1000 * MS);
        guard.press(BridgeNet.Defense.DODGE, impact + 10_000 * MS); // Drops every press older than KEPT.
        assert guard.judge(impact + 10_001 * MS, false) == AvatarCombat.Defense.PERFECT_DODGE
                && guard.judge(impact + 11_000 * MS, false) == AvatarCombat.Defense.GUARD : "The guard held for 10 s still guards";

        // Over the network, a press counts when the player made it: arrival less its wait on the client and the
        // allowance. At 100 ms of round trip the player sees the impact 50 ms late and dodges 40 ms before that: the press
        // is made 10 ms after the server's impact and arrives 60 ms after it, and still counts 40 ms before it (42 with
        // its 2 ms wait on the client).
        assert AvatarCombat.allowance(-1) == 0 && AvatarCombat.allowance(80) == 80 * MS && AvatarCombat.allowance(400) == AvatarCombat.MAX_ALLOWANCE;
        long client = 7 * MS; // Any client clock: only its differences count.
        var remote = new AvatarCombat.Fighter();
        long placed = AvatarCombat.placed(impact + 60 * MS, client, client + 2 * MS, AvatarCombat.allowance(100), false);
        assert placed == impact - 42 * MS : placed;
        remote.press(BridgeNet.Defense.DODGE, placed);
        assert remote.judge(impact, false) == AvatarCombat.Defense.PERFECT_DODGE : "The single-player window, through the round trip";
        var arrival = new AvatarCombat.Fighter();
        arrival.press(BridgeNet.Defense.DODGE, impact + 60 * MS);
        assert arrival.judge(impact, false) == AvatarCombat.Defense.HIT : "Judged on arrival, the same dodge would be a hit";
        // Beyond MAX_ALLOWANCE (300 ms of round trip), presses must come earlier by the excess.
        assert AvatarCombat.placed(impact + 260 * MS, client, client, AvatarCombat.allowance(300), false) == impact + 110 * MS;
        // The host's client shares the server's clock: the press counts when it happened, with nothing taken off.
        assert AvatarCombat.placed(impact, impact - 30 * MS, 0, 0, true) == impact - 30 * MS;
        assert AvatarCombat.placed(impact, impact - AvatarCombat.KEPT - 1, 0, 0, true) == Long.MIN_VALUE : "A stale press";
        // A host press handled after a 300 ms server stall (an autosave) still counts, and so does a guard release then.
        long stalled = impact + 300 * MS, key = impact - 50 * MS;
        assert AvatarCombat.placed(stalled, key, key + MS, 0, true) == key : "The server's own queue never refuses the host's press";
        var host = new AvatarCombat.Fighter();
        host.press(BridgeNet.Defense.GUARD_DOWN, impact - 2000 * MS);
        host.press(BridgeNet.Defense.GUARD_UP, AvatarCombat.placed(stalled, key, key + MS, 0, true));
        assert host.judge(impact + 3500 * MS, false) == AvatarCombat.Defense.HIT : "A release during a stall never leaves the guard held";
        assert AvatarCombat.placed(impact, impact + 1, 0, 0, true) == Long.MIN_VALUE : "A press from the future";
        assert AvatarCombat.placed(impact, client, client + AvatarCombat.MAX_WAIT + 1, 0, false) == Long.MIN_VALUE
                && AvatarCombat.placed(impact, client, client - 1, 0, false) == Long.MIN_VALUE : "A client wait out of bounds";
        // Held hits wait from HOLD_FROM: a LAN's few milliseconds judge at once.
        assert AvatarCombat.allowance(2) < AvatarCombat.HOLD_FROM && AvatarCombat.allowance(40) >= AvatarCombat.HOLD_FROM;

        // Strike validation: reach with a block of slack, rates per second, the rehit per target.
        assert AvatarCombat.reaches(36, false) && !AvatarCombat.reaches(36.01, false) : "Melee reach is 6 blocks";
        assert AvatarCombat.reaches(35 * 35, true) && !AvatarCombat.reaches(35.1 * 35.1, true) : "A shot's reach is 35 blocks";
        var striker = new AvatarCombat.Fighter();
        assert striker.admits(1, false, impact) && !striker.admits(1, false, impact + 199 * MS) && striker.admits(2, false, impact + 199 * MS)
                : "A target is struck again after the rehit only";
        assert striker.admits(1, false, impact + 200 * MS);
        var burst = new AvatarCombat.Fighter();
        for (int target = 0; target < AvatarCombat.STRIKES; target++) assert burst.admits(target, false, impact) : "Strike " + target;
        assert !burst.admits(999, false, impact) && burst.admits(999, false, impact + 1000 * MS) : "40 strikes a second, refilled";
        for (int shot = 0; shot < AvatarCombat.SHOTS; shot++) assert burst.admits(5, true, impact) : "Shots skip the rehit: " + shot;
        assert !burst.admits(5, true, impact) : "10 shots a second";
        // Link requests: each start scans the terrain window, and a payload skips vanilla's command spam limit.
        var linker = new AvatarCombat.Fighter();
        assert linker.links.take(impact) && linker.links.take(impact) && !linker.links.take(impact) && linker.links.take(impact + 500 * MS)
                : "2 link requests a second";

        // Smart Mines are capped per owner: a fourth mine replaces its owner's oldest, never another owner's.
        UUID first = new UUID(0, 1), second = new UUID(0, 2);
        List<AvatarItems.Mine> mines = new ArrayList<>();
        for (int k = 0; k < 3; k++) AvatarItems.place(mines, new AvatarItems.Mine(null, new Vec3d(k, 0, 0), first));
        for (int k = 0; k < 2; k++) AvatarItems.place(mines, new AvatarItems.Mine(null, new Vec3d(k, 0, 1), second));
        AvatarItems.place(mines, new AvatarItems.Mine(null, new Vec3d(3, 0, 0), first));
        assert mines.size() == 5 && mines.stream().filter(m -> m.owner.equals(second)).count() == 2
                && mines.stream().noneMatch(m -> m.at.equals(new Vec3d(0, 0, 0))) : "The first owner's oldest went";

        // A multiplayer silence: spared for GRACE, then hits land; answering again ends it, and the next silence starts anew.
        var silent = new AvatarCombat.Fighter();
        assert silent.graced(true, impact) && silent.graced(true, impact + AvatarCombat.GRACE - 1) && !silent.graced(true, impact + AvatarCombat.GRACE);
        assert !silent.graced(false, impact + 3000 * MS) && silent.graced(true, impact + 4000 * MS);

        // No PvP (entities need Minecraft's bootstrap, so this reads the compiled hook): damage whose attacker is another
        // player returns before anything is judged, remembered or earned; EVE's targets exclude anyone's pet and a player's mount.
        MethodNode defend = method("defend");
        int pvp = index(defend, "fromPlayer"), fighter = index(defend, "fighter"), judge = index(defend, "judge"), answer = index(defend, "answer");
        assert pvp >= 0 && pvp < fighter && fighter < judge && judge < answer : "The PvP guard comes before the player's Fighter";
        assert instanceOf(method("fromPlayer"), "net/minecraft/entity/player/PlayerEntity");
        MethodNode strikable = method("strikable");
        assert index(strikable, "getOwnerUuid") >= 0 && index(strikable, "hasPlayerRider") >= 0 && instanceOf(strikable, "net/minecraft/entity/player/PlayerEntity");
        System.out.println("Combat tests passed: per-player verdicts (the creeper case), guard cooldown and hold, presses placed through the round trip, strike validation, mines per owner, the multiplayer grace, no PvP");
    }

    private static MethodNode method(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var in = CombatTest.class.getClassLoader().getResourceAsStream("org/stellarbridge/AvatarCombat.class")) {
            new ClassReader(in).accept(node, 0);
        }
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    /** The instruction index of the first call to a method of that name, or -1. */
    private static int index(MethodNode method, String name) {
        for (int k = 0; k < method.instructions.size(); k++)
            if (method.instructions.get(k) instanceof MethodInsnNode call && call.name.equals(name)) return k;
        return -1;
    }

    private static boolean instanceOf(MethodNode method, String type) {
        for (var instruction : method.instructions)
            if (instruction instanceof TypeInsnNode check && check.getOpcode() == Opcodes.INSTANCEOF && check.desc.equals(type)) return true;
        return false;
    }
}

package org.stellarbridge;

import net.minecraft.client.input.Input;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.points.BeforeFinalReturn;
import org.spongepowered.asm.mixin.injection.points.BeforeReturn;
import org.spongepowered.asm.mixin.injection.struct.InjectionPointData;
import org.spongepowered.asm.util.Annotations;

/** Validate the actual pinned input boundaries without generating input or launching a window. */
public final class ControlHooksTest {
    public static void main(String[] args) throws Exception {
        ClassNode client = read("net/minecraft/client/MinecraftClient");
        for (String[] method : new String[][] {{"setScreen", "(Lnet/minecraft/client/gui/screen/Screen;)V"},
                {"setOverlay", "(Lnet/minecraft/client/gui/screen/Overlay;)V"}, {"onWindowFocusChanged", "(Z)V"},
                {"doAttack", "()Z"}, {"doItemUse", "()V"}, {"handleBlockBreaking", "(Z)V"}, {"handleInputEvents", "()V"}})
            assert hasMethod(client, method[0], method[1]);
        ClassNode keyboard = read("net/minecraft/client/Keyboard"), mouse = read("net/minecraft/client/Mouse");
        assert hasMethod(keyboard, "onKey", "(JIIII)V") && hasMethod(mouse, "onMouseButton", "(JIII)V");
        MethodNode onKey = keyboard.methods.stream().filter(method -> method.name.equals("onKey")).findFirst().orElseThrow();
        AbstractInsnNode releaseReturn = null;
        for (var instruction : onKey.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/option/KeyBinding")
                    && call.name.equals("setKeyPressed") && call.getPrevious().getOpcode() == org.objectweb.asm.Opcodes.ICONST_0) {
                releaseReturn = call;
                while (releaseReturn.getOpcode() != org.objectweb.asm.Opcodes.RETURN) releaseReturn = releaseReturn.getNext();
                break;
            }
        }
        assert releaseReturn != null : "Pinned keyboard release branch must be identified";
        assert !returns("TAIL", onKey).contains(releaseReturn) : "TAIL only reaches the later press return";
        MethodNode keyObserver = read("org/stellarbridge/mixin/ControlKeyboardMixin").methods.stream()
                .filter(method -> method.name.equals("stellarInput")).findFirst().orElseThrow();
        var at = Annotations.<java.util.List<AnnotationNode>>getValue(Annotations.getVisible(keyObserver, Inject.class), "at").getFirst();
        assert returns(Annotations.getValue(at, "value"), onKey).contains(releaseReturn)
                : "Keyboard observer misses releases; held-key blocking and scan codes can never clear";
        assert calls(mouse, "updateMouse", "net/minecraft/client/network/ClientPlayerEntity", "changeLookDirection", "(DD)V") == 1;
        ClassNode player = read("net/minecraft/client/network/ClientPlayerEntity");
        assert hasMethod(player, "move", "(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V");
        assert calls(player, "tickMovement", "net/minecraft/client/input/Input", "tick", "(ZF)V") == 1;
        assert calls(player, "tick", "net/minecraft/client/network/ClientPlayerEntity", "sendMovementPackets", "()V") == 1;
        assert player.fields.stream().anyMatch(field -> field.name.equals("ticksToNextAutojump") && field.desc.equals("I"));
        // Neither raw event observer may cancel keyboard/mouse handling for Minecraft screens or other mods.
        for (String mixin : new String[] {"ControlKeyboardMixin", "ControlMouseMixin"}) {
            ClassNode observer = read("org/stellarbridge/mixin/" + mixin);
            for (var method : observer.methods) for (var instruction : method.instructions)
                assert !(instruction instanceof MethodInsnNode call && call.owner.endsWith("CallbackInfo") && call.name.equals("cancel"));
        }
        ClassNode movement = read("org/stellarbridge/mixin/ClientPlayerMovementMixin");
        assert calls(movement, "stellarBridgeInput", "org/stellarbridge/ControlBridge", "suppressVanillaMovement",
                "(Lnet/minecraft/client/input/Input;)V") == 1;
        assert calls(movement, "stellarBridgeInput", "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel", "()V") == 0;
        Input input = new Input();
        input.movementForward = input.movementSideways = 1;
        input.pressingForward = input.pressingBack = input.pressingLeft = input.pressingRight = input.jumping = input.sneaking = true;
        ControlBridge.clearMovement(input, true);
        assert input.movementForward == 0 && input.movementSideways == 0 && !input.jumping;
        assert !input.pressingForward && !input.pressingBack && !input.pressingLeft && !input.pressingRight && input.sneaking;
        ControlBridge.clearMovement(input, false);
        assert !input.sneaking : "Native dodge must not also change Minecraft's sneak state";
        ControlBridge.alignPresentedLook(); // With no explicit play state, no client instance is needed or changed.
        ClassNode view = read("org/stellarbridge/CaptureView"), controls = read("org/stellarbridge/ControlBridge");
        boolean presented = false, aligned = false;
        for (var method : view.methods) if (method.name.equals("composite")) for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC
                    && field.name.equals("presented")) presented = true;
            if (instruction instanceof MethodInsnNode call && call.name.equals("alignPresentedLook")) {
                assert presented : "Placement facing must follow the successful presentation, not a queued newer frame";
                aligned = true;
            }
        }
        assert aligned;
        assert calls(controls, "alignPresentedLook", "org/stellarbridge/CaptureView", "controlCamera",
                "(Ljava/lang/String;Ljava/lang/String;J)Lorg/stellarbridge/CaptureProjection$CameraPose;") == 1;
        assert calls(controls, "alignPresentedLook", "net/minecraft/client/network/ClientPlayerEntity", "setYaw", "(F)V") == 1;
        assert calls(controls, "alignPresentedLook", "net/minecraft/client/network/ClientPlayerEntity", "setPitch", "(F)V") == 1;
        // EVE combat layout: use is the heavy attack (Y), the inventory key blocks (LB), hotbar 1 holds LB then adds X.
        boolean[] held = new boolean[30];
        long[] since = new long[30];
        held[8] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x8000 : "Use (right click) is Y, not LB";
        held[8] = false;
        held[15] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x100 : "The inventory key is LB";
        held[15] = false;
        held[ControlBridge.SKILLS] = true;
        since[ControlBridge.SKILLS] = 1_000;
        assert ControlBridge.buttons(held, since, 1_000) == 0x100 : "A skill first holds LB";
        assert ControlBridge.buttons(held, since, 1_000 + ControlBridge.SKILL_DELAY) == (0x100 | 0x4000) : "then adds its face button";
        held[ControlBridge.SKILLS] = false;
        held[ControlBridge.SKILLS + 3] = true;
        assert ControlBridge.buttons(held, since, ControlBridge.SKILL_DELAY) == (0x100 | 0x2000) : "Skill 4 is LB with B (face right)";
        held[ControlBridge.BURST] = true;
        assert ControlBridge.buttons(held, since, ControlBridge.SKILL_DELAY) == (0x200 | 0x2000) : "With the burst key, RB leads";
        held[ControlBridge.BURST] = false;
        held[ControlBridge.SKILLS + 3] = false;
        held[ControlBridge.AIM] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x10000 : "The aim key (Ctrl) aims the drone (LT)";
        held[ControlBridge.ATTACK] = true;
        assert ControlBridge.buttons(held, since, 0) == (0x10000 | 0x20000) : "Attack while aiming fires (RT), not X";
        held[ControlBridge.AIM] = false;
        assert ControlBridge.buttons(held, since, 0) == 0x4000 : "Attack alone is the light attack";
        held[ControlBridge.ATTACK] = false;
        // EVE's defense: the pinned server player's damage defers to AvatarCombat; the sneak key dodges (B), the inventory key guards.
        assert hasMethod(read("net/minecraft/server/network/ServerPlayerEntity"), "damage", "(Lnet/minecraft/entity/damage/DamageSource;F)Z");
        assert calls(read("org/stellarbridge/mixin/AvatarDefenseMixin"), "stellarEveDefends", "org/stellarbridge/AvatarCombat", "defend",
                "(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/entity/damage/DamageSource;F)Ljava/lang/Boolean;") == 1;
        held[ControlBridge.DODGE] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x2000 : "The sneak key dodges (B)";
        held[ControlBridge.DODGE] = false;
        held[ControlBridge.GUARD] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x100 : "The inventory key guards (LB)";
        held[ControlBridge.GUARD] = false;
        // Stellar Blade's HUD replaces the pinned health bar.
        assert hasMethod(read("net/minecraft/client/gui/hud/InGameHud"), "renderHealthBar",
                "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V");
        // Lock-on: the pinned outline query the locked target answers, and the camera angles toward it.
        assert hasMethod(read("net/minecraft/client/MinecraftClient"), "hasOutline", "(Lnet/minecraft/entity/Entity;)Z");
        float[] south = AvatarLock.angles(0, 0, 1), westUp = AvatarLock.angles(-1, 1, 0);
        assert Math.abs(south[0]) < 1e-4 && Math.abs(south[1]) < 1e-4 : "Minecraft yaw 0 faces +Z";
        assert Math.abs(westUp[0] - 90) < 1e-4 && Math.abs(westUp[1] + 45) < 1e-4 : "Yaw 90 faces -X; looking up is negative pitch";
        held[20] = true;
        assert ControlBridge.buttons(held, since, 0) == 0x40 : "The EVE sprint key is L3";
        held[20] = false;
        held[ControlBridge.WALK] = true;
        assert ControlBridge.buttons(held, since, 0) == 0 : "Walk only shapes the stick";
        held[ControlBridge.WALK] = false;
        held[6] = true;
        assert ControlBridge.buttons(held, since, 0) == 0 : "Minecraft's own sprint key does nothing for EVE";
        held[6] = false;
        int[] expected = {0x1, 0x2, 0x20000, 0x20, 0x80, 0x40 | 0x80}; // Heal, item, interact, scan, lock-on, Tachy (L3 with R3).
        for (int k = 0; k < expected.length; k++) {
            held[24 + k] = true;
            assert ControlBridge.buttons(held, since, 0) == expected[k] : "EVE key " + k;
            held[24 + k] = false;
        }
        System.out.println("Controller hook tests passed: pinned input/look/menu boundaries, normal event delivery, narrow movement authority and EVE's combat layout (skills, drone aim, sprint, walk)");
    }
    private static boolean hasMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
    }
    private static java.util.List<AbstractInsnNode> returns(String at, MethodNode target) {
        var data = new InjectionPointData(null, at, java.util.List.of(), "", "", -1, 0, null, 0) {
            @Override public org.spongepowered.asm.mixin.refmap.IMixinContext getMixin() { return null; }
        };
        var point = at.equals("RETURN") ? new BeforeReturn(data) : new BeforeFinalReturn(data);
        var selected = new java.util.ArrayList<AbstractInsnNode>();
        point.find(target.desc, target.instructions, selected);
        return selected;
    }
    private static int calls(ClassNode node, String methodName, String owner, String name, String descriptor) {
        int count = 0;
        for (var method : node.methods) if (method.name.equals(methodName)) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor)) count++;
        return count;
    }
    private static ClassNode read(String name) throws Exception {
        try (var input = ControlHooksTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing pinned class " + name);
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
}

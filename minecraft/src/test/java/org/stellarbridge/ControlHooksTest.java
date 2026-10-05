package org.stellarbridge;

import net.minecraft.client.input.Input;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

/** Validate the actual pinned input boundaries without generating input or launching a window. */
public final class ControlHooksTest {
    public static void main(String[] args) throws Exception {
        ClassNode client = read("net/minecraft/client/MinecraftClient");
        for (String[] method : new String[][] {{"setScreen", "(Lnet/minecraft/client/gui/screen/Screen;)V"},
                {"setOverlay", "(Lnet/minecraft/client/gui/screen/Overlay;)V"}, {"onWindowFocusChanged", "(Z)V"},
                {"doAttack", "()Z"}, {"doItemUse", "()V"}, {"handleBlockBreaking", "(Z)V"}})
            assert hasMethod(client, method[0], method[1]);
        ClassNode keyboard = read("net/minecraft/client/Keyboard"), mouse = read("net/minecraft/client/Mouse");
        assert hasMethod(keyboard, "onKey", "(JIIII)V") && hasMethod(mouse, "onMouseButton", "(JIII)V");
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
        System.out.println("Controller hook tests passed: pinned input/look/menu boundaries, normal event delivery and narrow movement authority");
    }
    private static boolean hasMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor));
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

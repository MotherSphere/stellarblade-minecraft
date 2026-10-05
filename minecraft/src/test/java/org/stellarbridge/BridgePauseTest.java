package org.stellarbridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Check the actual pinned bytecode boundary without starting a window or integrated server. */
public final class BridgePauseTest {
    public static void main(String[] args) throws Exception {
        ClassNode client = read("net/minecraft/client/MinecraftClient");
        var render = client.methods.stream().filter(method -> method.name.equals("render") && method.desc.equals("(Z)V"))
                .findFirst().orElseThrow();
        int stores = 0;
        for (var instruction : render.instructions) {
            if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.owner.equals(client.name) || !field.name.equals("paused") || !field.desc.equals("Z")) continue;
            stores++;
            var next = field.getNext();
            while (next != null && !(next instanceof MethodInsnNode)) next = next.getNext();
            assert next instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/render/RenderTickCounter$Dynamic")
                    && call.name.equals("tick") && call.desc.equals("(Z)V")
                    : "The pause override must run before the render tick counter consumes the new value";
        }
        assert stores == 1 : "The pinned render method must have exactly one paused-field assignment";

        ClassNode server = read("net/minecraft/server/integrated/IntegratedServer");
        boolean serverUsesClientPause = server.methods.stream().filter(method -> method.name.equals("tick"))
                .anyMatch(method -> {
                    for (var instruction : method.instructions)
                        if (instruction instanceof MethodInsnNode call && call.owner.equals(client.name)
                                && call.name.equals("isPaused") && call.desc.equals("()Z")) return true;
                    return false;
                });
        assert serverUsesClientPause : "The real integrated server must consume the same client pause flag";

        ClassNode mixin = read("org/stellarbridge/mixin/BridgePauseMixin");
        assert mixin.methods.stream().anyMatch(method -> {
            boolean guard = false, write = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("org/stellarbridge/StellarBridge")
                        && call.name.equals("keepsWorldRunning")) guard = true;
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("paused") && field.desc.equals("Z")) write = true;
            }
            return guard && write;
        }) : "The installed handler must guard the actual pause-field write";
        System.out.println("Bridge pause tests passed: pinned render assignment and integrated-server consumer verified");
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = BridgePauseTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assert input != null : "Required compiled pause integration is missing: " + name;
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}

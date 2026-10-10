package org.stellarbridge;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Every class a dedicated server can load (from the main entrypoint and the common and server mixins, following the
 * mod's own references) references no client-only type: nothing under net/minecraft/client/, Fabric's client APIs,
 * IntegratedServer, LWJGL or Blaze3D, and no class Minecraft's merged jar marks @Environment(CLIENT).
 */
public final class ServerSafetyTest {
    private static final String MOD = "org/stellarbridge/";
    private static final List<String> FORBIDDEN = List.of("net/minecraft/client/", "net/minecraft/server/integrated/",
            "org/lwjgl/", "com/mojang/blaze3d/", "net/fabricmc/fabric/api/client/", "net/fabricmc/fabric/impl/client/",
            "net/fabricmc/api/ClientModInitializer");
    private static final Map<String, ClassNode> classes = new HashMap<>();

    public static void main(String[] args) throws Exception {
        List<String> roots = new ArrayList<>();
        var mod = JsonParser.parseReader(resource("fabric.mod.json")).getAsJsonObject();
        mod.getAsJsonObject("entrypoints").getAsJsonArray("main").forEach(e -> roots.add(e.getAsString().replace('.', '/')));
        assert mod.get("environment").getAsString().equals("*") : "The mod must load on dedicated servers";
        var mixins = JsonParser.parseReader(resource("stellar_bridge.mixins.json")).getAsJsonObject();
        String mixinPackage = mixins.get("package").getAsString().replace('.', '/') + "/";
        for (String side : new String[] {"mixins", "server"})
            if (mixins.has(side)) mixins.getAsJsonArray(side).forEach(e -> roots.add(mixinPackage + e.getAsString()));
        assert roots.contains("org/stellarbridge/BridgeServer") && roots.contains("org/stellarbridge/mixin/AvatarDefenseMixin")
                && roots.contains("org/stellarbridge/mixin/ServerPlayerMovementMixin") : roots;

        Set<String> loaded = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        var queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!loaded.add(name)) continue;
            for (String problem : problems(name, references -> references.stream().filter(r -> r.startsWith(MOD)).forEach(queue::add)))
                problems.add(name + ": " + problem);
        }
        assert problems.isEmpty() : "Server-loaded classes reach client code:\n  " + String.join("\n  ", problems);
        // The guard itself: client classes must be caught, whether by package or by Minecraft's client-only marker.
        assert clientOnly(node("net/minecraft/client/MinecraftClient")) && !clientOnly(node("net/minecraft/server/MinecraftServer"));
        assert !problems("org/stellarbridge/StellarBridge", r -> { }).isEmpty() && !problems("org/stellarbridge/AvatarWeapons$Gun", r -> { }).isEmpty();
        assert loaded.contains("org/stellarbridge/AvatarCombat") && loaded.contains("org/stellarbridge/AvatarItems")
                && loaded.contains("org/stellarbridge/PoseRelay") && loaded.contains("org/stellarbridge/NetPose")
                && !loaded.contains("org/stellarbridge/StellarBridge") && !loaded.contains("org/stellarbridge/AvatarView")
                && !loaded.contains("org/stellarbridge/RemoteEves") && !loaded.contains("org/stellarbridge/AvatarStream") : loaded;
        System.out.println("Server safety tests passed: " + loaded.size() + " server-loaded classes reference no client code");
    }

    /** The client-only references of one class; its mod references go to follow. */
    private static List<String> problems(String name, java.util.function.Consumer<Set<String>> follow) throws Exception {
        ClassNode node = node(name);
        if (node == null) throw new AssertionError("Missing class " + name);
        Set<String> types = new TreeSet<>();
        if (node.superName != null) types.add(node.superName);
        types.addAll(node.interfaces);
        annotations(node.visibleAnnotations, types);
        annotations(node.invisibleAnnotations, types);
        node.fields.forEach(field -> descriptor(field.desc, types));
        for (var method : node.methods) {
            descriptor(method.desc, types);
            types.addAll(method.exceptions);
            annotations(method.visibleAnnotations, types);
            method.tryCatchBlocks.forEach(block -> { if (block.type != null) types.add(block.type); });
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    type(call.owner, types);
                    descriptor(call.desc, types);
                } else if (instruction instanceof FieldInsnNode field) {
                    type(field.owner, types);
                    descriptor(field.desc, types);
                } else if (instruction instanceof TypeInsnNode type) type(type.desc, types);
                else if (instruction instanceof MultiANewArrayInsnNode array) descriptor(array.desc, types);
                else if (instruction instanceof LdcInsnNode ldc) constant(ldc.cst, types);
                else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    descriptor(dynamic.desc, types);
                    constant(dynamic.bsm, types);
                    for (Object argument : dynamic.bsmArgs) constant(argument, types);
                } else if (instruction instanceof FrameNode frame) {
                    for (List<Object> slots : java.util.Arrays.asList(frame.local, frame.stack))
                        if (slots != null) for (Object slot : slots) if (slot instanceof String type) type(type, types);
                }
            }
        }
        follow.accept(types);
        List<String> found = new ArrayList<>();
        for (String type : types) {
            if (FORBIDDEN.stream().anyMatch(type::startsWith)) found.add(type);
            else if (!type.startsWith(MOD) && clientOnly(node(type))) found.add(type + " (client only)");
        }
        return found;
    }

    /** Whether the merged jar marks a class @Environment(CLIENT): the dedicated server's jar lacks it. */
    private static boolean clientOnly(ClassNode node) {
        if (node == null) return false;
        for (var annotations : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations))
            if (annotations != null) for (var annotation : annotations)
                if (annotation.desc.equals("Lnet/fabricmc/api/Environment;") && annotation.values != null
                        && annotation.values.get(1) instanceof String[] value && value[1].equals("CLIENT")) return true;
        return false;
    }

    private static void annotations(List<AnnotationNode> annotations, Set<String> types) {
        if (annotations != null) annotations.forEach(annotation -> descriptor(annotation.desc, types));
    }

    private static void constant(Object value, Set<String> types) {
        if (value instanceof Type type) descriptor(type.getDescriptor(), types);
        else if (value instanceof Handle handle) {
            type(handle.getOwner(), types);
            descriptor(handle.getDesc(), types);
        }
    }

    private static void type(String internal, Set<String> types) {
        if (internal.startsWith("[")) descriptor(internal, types);
        else types.add(internal);
    }

    private static void descriptor(String descriptor, Set<String> types) {
        if (descriptor.startsWith("(")) {
            Type method = Type.getMethodType(descriptor);
            descriptor(method.getReturnType().getDescriptor(), types);
            for (Type argument : method.getArgumentTypes()) descriptor(argument.getDescriptor(), types);
            return;
        }
        Type type = Type.getType(descriptor);
        while (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) types.add(type.getInternalName());
    }

    private static ClassNode node(String name) throws Exception {
        if (classes.containsKey(name)) return classes.get(name);
        ClassNode node = null;
        try (var input = ServerSafetyTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input != null) {
                node = new ClassNode();
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG);
            }
        }
        classes.put(name, node);
        return node;
    }

    private static InputStreamReader resource(String name) {
        var input = ServerSafetyTest.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new AssertionError("Missing resource " + name);
        return new InputStreamReader(input, StandardCharsets.UTF_8);
    }
}

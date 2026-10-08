package org.stellarbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Offline timing of live avatar rebuilds from a private pack, in a hidden GL 3.2 core context. Not part of the suites
 * (needs a GPU and private files). Usage: AvatarRebuildCheck <pack dir> <mesh+mesh+...> ...: each argument is an outfit,
 * one component per mesh, rebuilt in order as AvatarView does after an equip: the build (meshes read, textures decoded)
 * as on its worker, the texture uploads in frames of AvatarView.UPLOAD_NANOS, then the renderer. The first outfit is
 * also loaded synchronously as replays load it, decoding on the calling thread.
 */
public final class AvatarRebuildCheck {
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]);
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "rebuild check", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL 3.2 core context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        RenderSystem.initRenderThread();
        System.out.println("GL " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
        Map<String, AvatarMesh> meshes = new LinkedHashMap<>(16, 0.75f, true);
        AvatarRenderer renderer = null;
        try (AvatarTextures textures = new AvatarTextures()) {
            for (int o = 1; o < args.length; o++) {
                List<AvatarRecording.Component> components = new ArrayList<>();
                for (String name : args[o].split("\\+")) {
                    String[] bones = java.util.Arrays.stream(AvatarMesh.read(pack.resolve(name + ".sbav")).bones).map(AvatarMesh.Bone::name).toArray(String[]::new);
                    components.add(new AvatarRecording.Component(name, name, null, bones, new String[0], 0));
                }
                if (o == 1) {
                    long start = System.nanoTime();
                    try (AvatarRenderer synchronous = new AvatarRenderer(AvatarModel.load(pack, components, new java.util.HashMap<>()), pack)) {
                        GL11.glFinish();
                        System.out.printf("outfit 1 loaded synchronously (as a replay): %.0f ms%n", (System.nanoTime() - start) / 1e6);
                    }
                }
                long start = System.nanoTime();
                AvatarModel model = AvatarModel.load(pack, components, meshes);
                AvatarModel.trim(meshes, model, AvatarView.MESH_BUDGET);
                long loaded = System.nanoTime();
                Map<String, AvatarTextures.Decoded> decoded = textures.decode(pack, AvatarRenderer.textures(model, pack));
                long built = System.nanoTime();
                int count = decoded.size(), frames = 0;
                long longest = 0;
                for (boolean done = false; !done; frames++) {
                    long frame = System.nanoTime();
                    done = textures.upload(decoded, AvatarView.UPLOAD_NANOS);
                    longest = Math.max(longest, System.nanoTime() - frame);
                }
                long uploaded = System.nanoTime();
                AvatarRenderer next = new AvatarRenderer(model, pack, textures);
                long constructed = System.nanoTime();
                if (renderer != null) renderer.close();
                renderer = next;
                textures.trim(renderer.textureKeys());
                GL11.glFinish();
                long finished = System.nanoTime();
                System.out.printf("outfit %d: build %.0f ms off the render thread (meshes %.0f ms, %d textures decoded); render thread: %d upload frames"
                                + " (longest %.1f ms, %.0f ms in all), renderer %.0f ms, GPU idle %.0f ms later; %d MB of textures kept%n",
                        o, (built - start) / 1e6, (loaded - start) / 1e6, count, frames, longest / 1e6, (uploaded - built) / 1e6,
                        (constructed - uploaded) / 1e6, (finished - constructed) / 1e6, textures.bytes() >> 20);
            }
        } finally {
            if (renderer != null) renderer.close();
        }
    }
}

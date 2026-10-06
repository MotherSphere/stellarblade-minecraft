package org.stellarbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImageWrite;

/**
 * Offline GPU check of a private avatar pack and pose recording: renders EVE with the mod's renderer
 * in a hidden GL 3.2 core context and writes a contact sheet. Not part of the suites (needs a GPU and private files).
 * Usage: AvatarRenderCheck <pack dir> <recording dir> <out.png> <seconds,...> [distance,height,degrees [glow]]: the camera
 * (default 3.2 blocks from her front, looking at 0.9 blocks) orbits by degrees around her feet; glow streams an orange
 * sword-edge glow as a special attack would.
 */
public final class AvatarRenderCheck {
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]), poses = Path.of(args[1]), out = Path.of(args[2]);
        String[] times = args[3].split(",");
        double[] camera = args.length > 4 ? java.util.Arrays.stream(args[4].split(",")).mapToDouble(Double::parseDouble).toArray()
                : new double[] {3.2, 0.9, 0};
        int tileWidth = 600, tileHeight = 900, width = tileWidth * times.length;
        if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW unavailable");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "avatar check", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL 3.2 core context");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        RenderSystem.initRenderThread();
        System.out.println("GL " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));

        int framebuffer = GL30.glGenFramebuffers(), color = GL30.glGenRenderbuffers(), depth = GL30.glGenRenderbuffers();
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, color);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, tileHeight);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH_COMPONENT24, width, tileHeight);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, color);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Framebuffer incomplete");
        int lightmap = GL11.glGenTextures(); // Full daylight: a white 16x16 lightmap.
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
        ByteBuffer white = BufferUtils.createByteBuffer(16 * 16 * 4);
        while (white.hasRemaining()) white.put((byte) 255);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white.flip());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        AvatarRecording recording = AvatarRecording.read(poses);
        AvatarModel model = AvatarModel.load(pack, recording.components, new java.util.HashMap<>());
        try (AvatarRenderer renderer = new AvatarRenderer(model, pack)) {
            if (args.length > 5 && args[5].equals("glow")) renderer.parameters(java.util.Map.of(
                    "CH_W_Sword_02_Blade_Edge/Opacity", new float[] {1}, "CH_W_Sword_02_Blade_Edge/Emissive_Intensity", new float[] {4},
                    "CH_W_Sword_02_Blade_Edge/Emissive_Color", new float[] {1, 0.35f, 0.05f, 1}));
            int[] sections = renderer.sectionCounts();
            System.out.println(model.parts.size() + " meshes, sections drawn " + sections[0] + "/" + sections[1] + ", skipped " + model.skipped);
            GL11.glClearColor(0.47f, 0.65f, 1f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            AvatarRecording.Pose pose = recording.emptyPose();
            for (int i = 0; i < times.length; i++) {
                recording.sample(Double.parseDouble(times[i]), pose);
                float[] root = pose.world()[model.parts.get(0).component()];
                // EVE's feet at the Minecraft origin; the camera faces her front (body component +X) from 3.2 blocks.
                Matrix4d body = AvatarMesh.transform(root, 0, new Matrix4d());
                Vector3d forward = AvatarModel.UNREAL_TO_MINECRAFT.transformDirection(body.transformDirection(new Vector3d(1, 0, 0))).setComponent(1, 0).normalize();
                forward.rotateY(Math.toRadians(camera[2]));
                Vector3d eye = new Vector3d(forward).mul(camera[0]).add(0, camera[1] + 0.1, 0), target = new Vector3d(0, camera[1], 0);
                Matrix4d placement = new Matrix4d().translation(-eye.x, -eye.y, -eye.z).mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-root[4], -root[5], -root[6]);
                model.palettes(pose, placement, renderer.palettes, false);
                for (int p = 0; p < model.parts.size(); p++) System.out.println("  " + bounds(model.parts.get(p).mesh(), model.morphWeights[p], renderer.palettes[p], eye));
                Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) (target.x - eye.x), (float) (target.y - eye.y), (float) (target.z - eye.z), 0, 1, 0);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(40), (float) tileWidth / tileHeight, 0.05f, 100);
                GL11.glViewport(i * tileWidth, 0, tileWidth, tileHeight);
                long started = System.nanoTime();
                // Daylight: the clear color as sky, a pale horizon as fog color (fog itself starts beyond the scene).
                renderer.draw(projection.mul(view), lightmap, 15, 15, 1000, 2000, new float[] {0.75f, 0.84f, 1f, 1f},
                        new float[] {0.47f, 0.65f, 1f});
                GL11.glFinish();
                System.out.printf("t=%s s: draw + finish %.2f ms%n", times[i], (System.nanoTime() - started) / 1e6);
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) throw new IllegalStateException("GL error " + error);
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * tileHeight * 4);
        GL11.glReadPixels(0, 0, width, tileHeight, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        STBImageWrite.stbi_flip_vertically_on_write(true);
        if (!STBImageWrite.stbi_write_png(out.toString(), width, tileHeight, 4, pixels, width * 4)) throw new IllegalStateException("PNG write failed");
        System.out.println("wrote " + out);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /** CPU copy of the vertex shader's morphing and skinning: the part's extent around EVE's feet, in blocks. */
    static String bounds(AvatarMesh mesh, float[] weights, java.nio.FloatBuffer palette, Vector3d eye) {
        double[] low = {1e9, 1e9, 1e9}, high = {-1e9, -1e9, -1e9};
        java.nio.FloatBuffer offsets = java.nio.FloatBuffer.allocate(6 * mesh.vertexCount);
        mesh.morph(weights, offsets);
        for (int v = 0; v < mesh.vertexCount; v++) {
            int at = v * AvatarMesh.VERTEX_BYTES;
            double[] p = {mesh.vertices.getFloat(at) + offsets.get(6 * v), mesh.vertices.getFloat(at + 4) + offsets.get(6 * v + 1),
                    mesh.vertices.getFloat(at + 8) + offsets.get(6 * v + 2)};
            for (int row = 0; row < 3; row++) {
                double value = 0;
                for (int k = 0; k < 4; k++) {
                    int bone = Short.toUnsignedInt(mesh.vertices.getShort(at + 32 + 2 * k)), base = bone * 12 + row * 4;
                    float weight = mesh.vertices.getFloat(at + 40 + 4 * k);
                    value += weight * (palette.get(base) * p[0] + palette.get(base + 1) * p[1] + palette.get(base + 2) * p[2] + palette.get(base + 3));
                }
                value += row == 0 ? eye.x : row == 1 ? eye.y : eye.z;
                low[row] = Math.min(low[row], value);
                high[row] = Math.max(high[row], value);
            }
        }
        return String.format("%s x %.2f..%.2f y %.2f..%.2f z %.2f..%.2f", mesh.name, low[0], high[0], low[1], high[1], low[2], high[2]);
    }
}

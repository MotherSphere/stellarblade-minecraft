package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImageWrite;

/**
 * Offline GPU check of a private avatar pack and pose recording: renders EVE with the mod's renderer
 * in a hidden GL 3.2 core context and writes a contact sheet. Not part of the suites (needs a GPU and private files).
 * Usage: AvatarRenderCheck <pack dir> <recording dir> <out.png> <seconds,...> [distance,height,degrees[,target] [glow | hurt | fx=A+B]]
 * [sun=elevation,azimuth]: the camera (default 3.2 blocks from her front, looking at 0.9 blocks, or at target) orbits by degrees
 * around her feet; glow streams an orange sword-edge glow as a special attack would; hurt draws her red hurt flash; fx plays those effect assets for 0.2 s before each frame, at the blade
 * tip for NS_A_ParTrail ones and at the hilt otherwise. A last argument entity draws her as under a shader pack, through
 * Minecraft's own cut-out entity and eyes programs (read from the client jar), with a sword glow. sun (degrees, azimuth from
 * +Z toward +X) puts a floor, a three-block wall and a slab around her, lights her with that sun, draws her cast shadow and
 * prints the GPU time of her draw, her shadow map and the cast faces; with entity, it draws instead what a shader pack's
 * shadow pass receives: her opaque and cut-out sections seen from that sun.
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
        boolean entity = java.util.List.of(args).contains("entity");
        String sunArgument = java.util.Arrays.stream(args).filter(a -> a.startsWith("sun=")).findFirst().orElse(null);
        AvatarShadows.Light key = AvatarShadows.NONE;
        if (sunArgument != null) {
            String[] angles = sunArgument.substring(4).split(",");
            double elevation = Math.toRadians(Double.parseDouble(angles[0])), azimuth = Math.toRadians(Double.parseDouble(angles[1]));
            key = new AvatarShadows.Light(new Vector3f((float) (Math.cos(elevation) * Math.sin(azimuth)), (float) Math.sin(elevation),
                    (float) (Math.cos(elevation) * Math.cos(azimuth))), 1);
        }
        // The sun's scene: a floor below y = 0, a three-block wall at x = -3 for |z| <= 3 and a slab at (1, 0, -2), in full light.
        AvatarShadows.Cells cells = new AvatarShadows.Cells() {
            @Override public float top(int x, int y, int z) {
                if (y < 0 || (x == -3 && y < 3 && Math.abs(z) <= 3)) return 1;
                return x == 1 && y == 0 && z == -2 ? 0.5f : 0;
            }
            @Override public float brightness(int x, int y, int z) { return 1; }
        };
        java.nio.FloatBuffer faces = BufferUtils.createFloatBuffer(7 * 6 * 4096), ground = BufferUtils.createFloatBuffer(7 * 6 * 4096);
        net.minecraft.client.gl.ShaderProgram cutout = entity ? vanilla("rendertype_entity_cutout_no_cull") : null;
        net.minecraft.client.gl.ShaderProgram translucent = entity ? vanilla("rendertype_entity_translucent") : null;
        net.minecraft.client.gl.ShaderProgram eyes = entity ? vanilla("rendertype_eyes") : null;
        int overlay = entity ? overlay() : 0;
        RenderSystem.setShaderLights(new org.joml.Vector3f(0.2f, 1, -0.7f).normalize(), new org.joml.Vector3f(-0.2f, 1, 0.7f).normalize());
        RenderSystem.setShaderFogStart(1000); // As the own shaders' fog below: none within the scene.
        RenderSystem.setShaderFogEnd(2000);
        try (AvatarRenderer renderer = new AvatarRenderer(model, pack)) {
            if (args.length > 5 && (args[5].equals("glow") || entity)) renderer.parameters(java.util.Map.of(
                    "CH_W_Sword_02_Blade_Edge/Opacity", new float[] {1}, "CH_W_Sword_02_Blade_Edge/Emissive_Intensity", new float[] {4},
                    "CH_W_Sword_02_Blade_Edge/Emissive_Color", new float[] {1, 0.35f, 0.05f, 1}));
            if (args.length > 5 && args[5].equals("hurt")) renderer.hurt(true);
            int terrain = sunArgument == null ? 0 : AvatarRenderer.link(flat(GL20.GL_VERTEX_SHADER, FLAT_VERTEX),
                    flat(GL20.GL_FRAGMENT_SHADER, FLAT_FRAGMENT), new String[] {"Position", "Normal", "Brightness"});
            int[] sections = renderer.sectionCounts();
            System.out.println(model.parts.size() + " meshes, sections drawn " + sections[0] + "/" + sections[1] + ", skipped " + model.skipped);
            GL11.glClearColor(0.47f, 0.65f, 1f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            AvatarRecording.Pose pose = recording.emptyPose();
            // Swings in the recording: when the weapon trail would show.
            StringBuilder swings = new StringBuilder();
            for (double t = 0; t < recording.duration(); t += 1 / 120.0) {
                recording.sample(t, pose);
                Matrix4d at = new Matrix4d().mul(AvatarModel.UNREAL_TO_MINECRAFT);
                model.palettes(pose, at, renderer.palettes, false);
                renderer.sampleTrail(pose, at, 0, 0, 0, t);
                if (renderer.trailStrength() > 0.5) swings.append(String.format("%.2f ", t));
            }
            System.out.println("trail above half strength at: " + (swings.length() == 0 ? "never" : swings));
            for (int i = 0; i < times.length; i++) {
                recording.sample(Double.parseDouble(times[i]), pose);
                float[] root = pose.world()[model.parts.get(0).component()];
                // EVE's feet at the Minecraft origin; the camera faces her front (body component +X) from 3.2 blocks.
                Matrix4d body = AvatarMesh.transform(root, 0, new Matrix4d());
                Vector3d forward = AvatarModel.UNREAL_TO_MINECRAFT.transformDirection(body.transformDirection(new Vector3d(1, 0, 0))).setComponent(1, 0).normalize();
                forward.rotateY(Math.toRadians(camera[2]));
                Vector3d eye = new Vector3d(forward).mul(camera[0]).add(0, camera[1] + 0.1, 0), target = new Vector3d(0, camera.length > 3 ? camera[3] : camera[1], 0);
                Matrix4d placement = new Matrix4d().translation(-eye.x, -eye.y, -eye.z).mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-root[4], -root[5], -root[6]);
                // The trail's last 0.3 s, sampled as Minecraft frames would.
                double now = Double.parseDouble(times[i]);
                for (double t = Math.max(0, now - 0.3); t < now; t += 1 / 120.0) {
                    recording.sample(t, pose);
                    model.palettes(pose, placement, renderer.palettes, false);
                    renderer.sampleTrail(pose, placement, eye.x, eye.y, eye.z, t);
                }
                recording.sample(now, pose);
                model.palettes(pose, placement, renderer.palettes, false);
                renderer.sampleTrail(pose, placement, eye.x, eye.y, eye.z, now);
                if (args.length > 5 && args[5].startsWith("fx=")) {
                    Matrix4f look = new Matrix4f().lookAt(0, 0, 0, (float) (target.x - eye.x), (float) (target.y - eye.y), (float) (target.z - eye.z), 0, 1, 0);
                    org.joml.Quaternionf facing = look.getNormalizedRotation(new org.joml.Quaternionf()).conjugate();
                    Vector3d hilt = new Vector3d(), tip = new Vector3d();
                    for (double t = now - 0.2; t <= now + 1e-9; t += 1 / 120.0) {
                        java.util.Map<String, Vector3d> active = new java.util.HashMap<>();
                        if (renderer.blade(hilt, tip))
                            for (String asset : args[5].substring(3).split("\\+")) active.put(asset, new Vector3d(asset.startsWith("NS_A_ParTrail") ? tip : hilt));
                        renderer.effects(active, eye, facing, t);
                    }
                }
                for (int p = 0; p < model.parts.size(); p++) System.out.println("  " + bounds(model.parts.get(p).mesh(), model.morphWeights[p], renderer.palettes[p], eye));
                Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) (target.x - eye.x), (float) (target.y - eye.y), (float) (target.z - eye.z), 0, 1, 0);
                Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(40), (float) tileWidth / tileHeight, 0.05f, 100);
                GL11.glViewport(i * tileWidth, 0, tileWidth, tileHeight);
                long started = System.nanoTime();
                // Daylight: the clear color as sky, a pale horizon as fog color (fog itself starts beyond the scene).
                Matrix4f projectionView = new Matrix4f(projection).mul(view);
                float[] fog = {0.75f, 0.84f, 1f, 1f}, sky = {0.47f, 0.65f, 1f};
                if (sunArgument != null) {
                    Vector3d low = new Vector3d(), high = new Vector3d();
                    Vector3f toward = AvatarShadows.toward(key);
                    Matrix4f light = new Matrix4f();
                    renderer.bounds(pose, placement, low, high);
                    float range = AvatarShadows.fit(low, high, toward, light);
                    if (entity) renderer.drawShadow(light, new Matrix4f(), lightmap, overlay, cutout);
                    else {
                        int vertices = AvatarShadows.faces(cells, low, high, toward, light, eye.x, eye.y, eye.z, faces);
                        faces.flip();
                        AvatarShadows.Light sun = key;
                        if (i == 0) {
                            double map = gpu(() -> renderer.castShadow(light, range));
                            double lit = gpu(() -> renderer.draw(projectionView, lightmap, 15, 15, 1000, 2000, fog, sky, sun));
                            double plain = gpu(() -> renderer.draw(projectionView, lightmap, 15, 15, 1000, 2000, fog, sky, AvatarShadows.NONE));
                            double cast = gpu(() -> renderer.drawCast(projectionView, faces, vertices, toward, 1000, 2000));
                            System.out.printf("GPU ms: EVE (no sun) %.3f, EVE (sun) %.3f, shadow map %.3f, cast faces %.3f%n", plain, lit, map, cast);
                            // The timing drew over the tile many times: clear it before the scene is drawn once.
                            GL11.glScissor(i * tileWidth, 0, tileWidth, tileHeight);
                            GL11.glEnable(GL11.GL_SCISSOR_TEST);
                            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                            GL11.glDisable(GL11.GL_SCISSOR_TEST);
                        }
                        drawFaces(terrain, projectionView, ground(cells, eye, ground));
                        renderer.castShadow(light, range);
                        renderer.drawCast(projectionView, faces, vertices, toward, 1000, 2000);
                        renderer.draw(projectionView, lightmap, 15, 15, 1000, 2000, fog, sky, sun);
                    }
                } else if (entity) renderer.drawEntity(view, projection, lightmap, overlay, 15, 15, cutout, translucent, eyes);
                else renderer.draw(projectionView, lightmap, 15, 15, 1000, 2000, fog, sky, key);
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

    /** Median GPU milliseconds of a pass over 300 runs, after 30 to warm up. */
    static double gpu(Runnable pass) {
        int query = GL15.glGenQueries();
        double[] times = new double[300];
        for (int k = -30; k < times.length; k++) {
            GL15.glBeginQuery(ARBTimerQuery.GL_TIME_ELAPSED, query);
            pass.run();
            GL15.glEndQuery(ARBTimerQuery.GL_TIME_ELAPSED);
            long nanos = ARBTimerQuery.glGetQueryObjecti64(query, GL15.GL_QUERY_RESULT);
            if (k >= 0) times[k] = nanos / 1e6;
        }
        GL15.glDeleteQueries(query);
        java.util.Arrays.sort(times);
        return times[times.length / 2];
    }

    // The sun's scene in Minecraft's face shading (top 1, north and south 0.8, east and west 0.6) on sand.
    private static final String FLAT_VERTEX = """
            #version 150
            in vec3 Position;
            in vec3 Normal;
            uniform mat4 ViewProjection;
            out float shade;
            void main() {
                gl_Position = ViewProjection * vec4(Position, 1.0);
                shade = Normal.y > 0.5 ? 1.0 : abs(Normal.z) > 0.5 ? 0.8 : 0.6;
            }
            """;
    private static final String FLAT_FRAGMENT = """
            #version 150
            in float shade;
            out vec4 fragColor;
            void main() { fragColor = vec4(vec3(0.86, 0.79, 0.58) * shade, 1.0); }
            """;
    private static int flat(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    /** The scene's visible faces within 8 blocks, camera-relative, in AvatarShadows.faces' layout: from above, each side, then the slabs' sides. */
    static java.nio.FloatBuffer ground(AvatarShadows.Cells cells, Vector3d eye, java.nio.FloatBuffer out) {
        out.clear();
        Matrix4f everywhere = new Matrix4f().scaling(1e-3f); // Every cell inside the shadow map's footprint.
        Vector3d low = new Vector3d(-8, -1, -8).sub(eye), high = new Vector3d(8, 3, 8).sub(eye);
        java.nio.FloatBuffer part = BufferUtils.createFloatBuffer(out.capacity());
        for (Vector3f toward : new Vector3f[] {new Vector3f(0, 1, 0), new Vector3f(1, 0.3f, 0).normalize(), new Vector3f(-1, 0.3f, 0).normalize(),
                new Vector3f(0, 0.3f, 1).normalize(), new Vector3f(0, 0.3f, -1).normalize()}) {
            int n = AvatarShadows.faces(cells, low, high, toward, everywhere, eye.x, eye.y, eye.z, part);
            for (int v = 0; v < n; v++) // Tops from above, sides from the sides.
                if ((toward.y > 0.99f) == (part.get(7 * v + 4) > 0.5f)) for (int c = 0; c < 7; c++) out.put(part.get(7 * v + c));
        }
        // faces() gives a slab its top only; its sides hide the floor's top under it, as in Minecraft.
        for (int x = -8; x <= 8; x++) for (int y = -1; y <= 3; y++) for (int z = -8; z <= 8; z++) {
            float top = cells.top(x, y, z);
            if (top <= 0 || top >= 1) continue;
            float rx = (float) (x - eye.x), ry = (float) (y - eye.y), rz = (float) (z - eye.z);
            AvatarShadows.face(out, rx + 1, ry, rz, 0, top, 0, 0, 0, 1, 1, 0, 0, 1);
            AvatarShadows.face(out, rx, ry, rz, 0, 0, 1, 0, top, 0, -1, 0, 0, 1);
            AvatarShadows.face(out, rx, ry, rz + 1, 1, 0, 0, 0, top, 0, 0, 0, 1, 1);
            AvatarShadows.face(out, rx, ry, rz, 0, top, 0, 1, 0, 0, 0, 0, -1, 1);
        }
        return out.flip();
    }

    /** Draws faces in ground's layout with the flat program, depth tested and written, back faces culled. */
    private static void drawFaces(int program, Matrix4f projectionView, java.nio.FloatBuffer faces) {
        int array = GL30.glGenVertexArrays(), buffer = GL15.glGenBuffers();
        GlStateManager._glBindVertexArray(array);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, faces, GL15.GL_STATIC_DRAW);
        for (int a = 0; a < 3; a++) GL20.glEnableVertexAttribArray(a);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 28, 12);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, 28, 24);
        GlStateManager._glUseProgram(program);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "ViewProjection"), false, projectionView.get(stack.mallocFloat(16)));
        }
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);
        GlStateManager._enableCull();
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, faces.limit() / 7);
        GlStateManager._disableCull();
        GlStateManager._glUseProgram(0);
        GlStateManager._glBindVertexArray(0);
        GL15.glDeleteBuffers(buffer);
        GL30.glDeleteVertexArrays(array);
    }

    /** One of Minecraft's core programs, from the client jar on the classpath, in the entity vertex format. */
    static net.minecraft.client.gl.ShaderProgram vanilla(String name) throws java.io.IOException {
        var jar = (net.minecraft.resource.ResourcePack) java.lang.reflect.Proxy.newProxyInstance(AvatarRenderCheck.class.getClassLoader(),
                new Class<?>[] {net.minecraft.resource.ResourcePack.class}, (proxy, method, arguments) -> method.getName().equals("getId") ? "vanilla" : null);
        return new net.minecraft.client.gl.ShaderProgram(id -> {
            String path = "/assets/" + id.getNamespace() + "/" + id.getPath();
            return AvatarRenderCheck.class.getResource(path) == null ? java.util.Optional.empty()
                    : java.util.Optional.of(new net.minecraft.resource.Resource(jar, () -> AvatarRenderCheck.class.getResourceAsStream(path)));
        }, name, net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
    }

    /** Minecraft's overlay texture: red hurt rows below 8, opaque white (no tint) from 8. */
    static int overlay() {
        ByteBuffer texels = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) texels.put(v < 8 ? new byte[] {(byte) 255, 0, 0, (byte) 0xb2} : new byte[] {-1, -1, -1, -1});
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels.flip());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return id;
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

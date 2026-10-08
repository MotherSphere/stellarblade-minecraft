package org.stellarbridge;

import java.nio.FloatBuffer;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * EVE's sun and shadows in her own shaders (no shader pack): the sun where Minecraft's sky draws it, a shadow map of EVE
 * fitted to her bones, and the block faces her shadow falls on, darkened as Minecraft darkens them under an entity.
 */
final class AvatarShadows {
    // The clear sun's share of EVE's directional light; the rest stays Minecraft's two entity lights, so the side away
    // from the sun keeps some of Minecraft's even look, as the world's blocks do.
    static final float KEY = 0.65f;
    static final double MIN_ELEVATION = Math.toRadians(15); // Lowest light her cast shadow follows: about 6 blocks long.
    static final float FADE_END = 6; // Blocks below EVE where her cast shadow has faded out (cast.fsh).
    // ponytail: one pad around her bones covers the sword's blade past its bone; a per-mesh reach if a longer weapon outgrows it.
    static final float PAD = 1;

    /** Unit direction toward the sun and how much of it reaches EVE, 0 to 1 (0: today's shading, a shadow straight down). */
    record Light(Vector3f direction, float strength) { }
    static final Light NONE = new Light(new Vector3f(0, 1, 0), 0);

    /** The sun over EVE, skyLight being the sky light level at her hips: none where Minecraft draws none (Nether, End). */
    static Light sky(ClientWorld world, float tickDelta, int skyLight) {
        if (!world.getDimension().hasSkyLight() || world.getDimensionEffects().getSkyType() != DimensionEffects.SkyType.NORMAL) return NONE;
        return sun(world.getSkyAngle(tickDelta), world.getRainGradient(tickDelta), skyLight);
    }

    /**
     * WorldRenderer.renderSky turns the sun by -90 degrees about Y, then by the sky angle about X: toward it is (-sin, cos, 0)
     * of the angle, up at noon (angle 0), rising in the east (+X). Rain hides it as it hides the sun's disc; sky light
     * below 15 (a canopy, a roof, a cave) fades it out by 11.
     */
    // ponytail: the sun only; a moon key (the opposite direction, times getMoonSize) if night play wants shadows.
    static Light sun(float skyAngle, float rain, int skyLight) {
        double radians = skyAngle * 2 * Math.PI;
        Vector3f toward = new Vector3f((float) -Math.sin(radians), (float) Math.cos(radians), 0);
        return new Light(toward, smoothstep(-0.05f, 0.2f, toward.y) * (1 - rain) * Math.clamp((skyLight - 11) / 4f, 0, 1));
    }

    /** Toward the light that casts EVE's shadow: straight up without a key light, never lower than MIN_ELEVATION. */
    static Vector3f toward(Light key) {
        Vector3f toward = new Vector3f(0, 1, 0).lerp(key.direction(), key.strength()).normalize();
        float least = (float) Math.sin(MIN_ELEVATION);
        if (toward.y < least) {
            float across = (float) Math.cos(MIN_ELEVATION) / (float) Math.hypot(toward.x, toward.z);
            toward.set(toward.x * across, least, toward.z * across);
        }
        return toward;
    }

    /**
     * Fits an orthographic view from the light (toward it) around a camera-relative box padded by a block; out maps
     * camera-relative blocks to its clip space. Returns its depth in blocks.
     */
    static float fit(Vector3d low, Vector3d high, Vector3f toward, Matrix4f out) {
        float pad = PAD;
        Vector3f center = new Vector3f((float) (low.x + high.x) / 2, (float) (low.y + high.y) / 2, (float) (low.z + high.z) / 2);
        boolean vertical = Math.abs(toward.y) > 0.99f;
        Matrix4f view = new Matrix4f().lookAt(center.x + toward.x * 8, center.y + toward.y * 8, center.z + toward.z * 8,
                center.x, center.y, center.z, 0, vertical ? 0 : 1, vertical ? 1 : 0);
        Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), corner = new Vector3f();
        for (int k = 0; k < 8; k++) {
            view.transformPosition((float) ((k & 1) == 0 ? low.x - pad : high.x + pad), (float) ((k & 2) == 0 ? low.y - pad : high.y + pad),
                    (float) ((k & 4) == 0 ? low.z - pad : high.z + pad), corner);
            min.min(corner);
            max.max(corner);
        }
        out.setOrtho(min.x, max.x, min.y, max.y, -max.z, -min.z).mul(view);
        return max.z - min.z;
    }

    /** The blocks the face search reads: a cell's top (1 for a full cube) and the brightness in front of a face. */
    interface Cells {
        /** 1 for a full cube; the top of a block covering its whole cell below it (a slab, a path); 0 for others. */
        float top(int x, int y, int z);
        /** Minecraft's brightness of the cell's light level; 0 where Minecraft draws no entity shadow (level 3 or less). */
        float brightness(int x, int y, int z);
    }

    static Cells cells(ClientWorld world) {
        BlockPos.Mutable at = new BlockPos.Mutable();
        return new Cells() {
            @Override public float top(int x, int y, int z) {
                BlockState state = world.getBlockState(at.set(x, y, z));
                if (state.getRenderType() == BlockRenderType.INVISIBLE) return 0;
                if (state.isFullCube(world, at)) return 1;
                VoxelShape shape = state.getOutlineShape(world, at);
                if (shape.isEmpty()) return 0;
                Box box = shape.getBoundingBox();
                return box.minX <= 0 && box.minZ <= 0 && box.maxX >= 1 && box.maxZ >= 1 && box.maxY < 1 ? (float) box.maxY : 0;
            }
            @Override public float brightness(int x, int y, int z) {
                int level = world.getLightLevel(at.set(x, y, z));
                return level <= 3 ? 0 : LightmapTextureManager.getBrightness(world.getDimension(), level);
            }
        };
    }

    /**
     * Writes the faces EVE's shadow can fall on as two triangles each (camera-relative position, normal, brightness per
     * vertex): block tops and the sides turned to the light, open in front, within the shadow map's footprint and down to
     * FADE_END blocks below her bones' box (low, high: camera-relative). Returns the vertex count; stops when out is full.
     */
    // ponytail: only cubes, flat tops and sides turned to the light receive her shadow; a deferred decal from a depth
    // copy if stairs, fences or mobs must.
    static int faces(Cells cells, Vector3d low, Vector3d high, Vector3f toward, Matrix4f light, double cameraX, double cameraY,
                     double cameraZ, FloatBuffer out) {
        out.clear();
        double drop = high.y - low.y + FADE_END, run = drop / toward.y;
        double reachX = -toward.x * run, reachZ = -toward.z * run;
        int x0 = floor(cameraX + low.x - PAD + Math.min(0, reachX)), x1 = floor(cameraX + high.x + PAD + Math.max(0, reachX));
        int z0 = floor(cameraZ + low.z - PAD + Math.min(0, reachZ)), z1 = floor(cameraZ + high.z + PAD + Math.max(0, reachZ));
        int y0 = floor(cameraY + low.y - PAD - FADE_END), y1 = floor(cameraY + high.y + PAD);
        // A cell whose center lies more than half a block diagonal (0.87) outside the shadow map's square holds no shadow.
        float marginX = 0.87f * (float) Math.sqrt(light.m00() * light.m00() + light.m10() * light.m10() + light.m20() * light.m20());
        float marginY = 0.87f * (float) Math.sqrt(light.m01() * light.m01() + light.m11() * light.m11() + light.m21() * light.m21());
        Vector3f clip = new Vector3f();
        int vertices = 0;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    if (out.remaining() < 3 * 42) return vertices;
                    light.transformProject((float) (x + 0.5 - cameraX), (float) (y + 0.5 - cameraY), (float) (z + 0.5 - cameraZ), clip);
                    if (Math.abs(clip.x) > 1 + marginX || Math.abs(clip.y) > 1 + marginY) continue;
                    float top = cells.top(x, y, z);
                    if (top <= 0) continue;
                    float rx = (float) (x - cameraX), ry = (float) (y - cameraY), rz = (float) (z - cameraZ);
                    float b = top < 1 ? cells.brightness(x, y, z) : cells.top(x, y + 1, z) < 1 ? cells.brightness(x, y + 1, z) : 0;
                    if (b > 0) vertices += face(out, rx, ry + top, rz, 0, 0, 1, 1, 0, 0, 0, 1, 0, b);
                    if (top < 1) continue;
                    // Sides of full cubes facing the light: east, west, south, north.
                    for (int side = 0; side < 4; side++) {
                        int dx = side == 0 ? 1 : side == 1 ? -1 : 0, dz = side == 2 ? 1 : side == 3 ? -1 : 0;
                        if (dx * toward.x + dz * toward.z <= 0.02f || cells.top(x + dx, y, z + dz) >= 1) continue;
                        float n = cells.brightness(x + dx, y, z + dz);
                        if (n <= 0) continue;
                        vertices += switch (side) {
                            case 0 -> face(out, rx + 1, ry, rz, 0, 1, 0, 0, 0, 1, 1, 0, 0, n);
                            case 1 -> face(out, rx, ry, rz, 0, 0, 1, 0, 1, 0, -1, 0, 0, n);
                            case 2 -> face(out, rx, ry, rz + 1, 1, 0, 0, 0, 1, 0, 0, 0, 1, n);
                            default -> face(out, rx, ry, rz, 0, 1, 0, 1, 0, 0, 0, 0, -1, n);
                        };
                    }
                }
        return vertices;
    }

    /** One unit face from an origin along u then v (u x v its normal, counter-clockwise from the front): six vertices. */
    private static int face(FloatBuffer out, float ox, float oy, float oz, float ux, float uy, float uz, float vx, float vy, float vz,
                            float nx, float ny, float nz, float brightness) {
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
        for (float[] c : corners)
            out.put(ox + ux * c[0] + vx * c[1]).put(oy + uy * c[0] + vy * c[1]).put(oz + uz * c[0] + vz * c[1]).put(nx).put(ny).put(nz).put(brightness);
        return 6;
    }

    private static int floor(double value) { return (int) Math.floor(value); }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}

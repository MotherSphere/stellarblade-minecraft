package org.stellarbridge;

import java.util.List;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Minecraft particles where Stellar Blade plays its effects on EVE, chosen by effect asset name: blade sparks, the red
 * signal of a charged heavy attack, electricity, the sword flame, spin and kick trails. The game's Cascade blade trails
 * are AvatarTrail's. Render thread only.
 */
final class AvatarEffects {
    /** How an effect shows in Minecraft: particle kind, particles per second, jitter radius and speed in blocks. */
    enum Look {
        SPARKS(90, 0.1, 0.12), SIGNAL(120, 0.25, 0.02), ELECTRIC(60, 0.4, 0.05), FLAME(50, 0.3, 0.01), SWEEP(6, 0.6, 0),
        PUFF(25, 0.25, 0.03), GLINT(30, 0.2, 0.05);

        final double rate, spread, speed;
        Look(double rate, double spread, double speed) {
            this.rate = rate;
            this.spread = spread;
            this.speed = speed;
        }
        ParticleEffect particle() {
            return switch (this) {
                case SPARKS, ELECTRIC -> ParticleTypes.ELECTRIC_SPARK;
                case SIGNAL -> new DustParticleEffect(new Vector3f(1f, 0.08f, 0.04f), 1.3f);
                case FLAME -> ParticleTypes.FLAME;
                case SWEEP -> ParticleTypes.SWEEP_ATTACK;
                case PUFF -> ParticleTypes.CLOUD;
                case GLINT -> ParticleTypes.CRIT;
            };
        }
    }

    /** The look of an effect asset, or null for the blade trails AvatarTrail draws. */
    static Look look(String asset) {
        if (asset.startsWith("P_A_AnimTrail")) return null;
        if (asset.contains("Signal")) return Look.SIGNAL;
        if (asset.contains("Electric")) return Look.ELECTRIC;
        if (asset.contains("Flame")) return Look.FLAME;
        if (asset.contains("ParTrail")) return Look.SPARKS;
        if (asset.contains("SpinTrail") || asset.contains("MeshTrail") || asset.contains("ChargeSlash")) return Look.SWEEP;
        if (asset.contains("Kick") || asset.contains("Wind")) return Look.PUFF;
        return Look.GLINT;
    }

    private final Random random = new Random();
    private final double[] owed = new double[AvatarStream.MAX_EFFECTS];
    private final Vector3d at = new Vector3d();
    private long lastNanos;

    /** Spawns this frame's share of particles for the active effects; placement maps Unreal world to camera-relative. */
    void spawn(List<AvatarStream.Effect> effects, Matrix4d placement, Vec3d camera, long nanos) {
        double seconds = lastNanos == 0 ? 0 : Math.min((nanos - lastNanos) / 1e9, 0.1);
        lastNanos = nanos;
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        for (int i = 0; i < effects.size() && i < owed.length; i++) {
            AvatarStream.Effect effect = effects.get(i);
            Look look = look(effect.name());
            if (look == null) continue;
            owed[i] += look.rate * seconds;
            placement.transformPosition(at.set(effect.x(), effect.y(), effect.z())).add(camera.x, camera.y, camera.z);
            ParticleEffect particle = owed[i] >= 1 ? look.particle() : null;
            for (; owed[i] >= 1; owed[i]--)
                world.addParticle(particle, at.x + jitter(look.spread), at.y + jitter(look.spread), at.z + jitter(look.spread),
                        jitter(look.speed), jitter(look.speed), jitter(look.speed));
        }
        for (int i = effects.size(); i < owed.length; i++) owed[i] = 0;
    }

    private double jitter(double size) {
        return (random.nextDouble() * 2 - 1) * size;
    }
}

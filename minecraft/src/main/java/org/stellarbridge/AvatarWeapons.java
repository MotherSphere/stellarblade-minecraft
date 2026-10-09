package org.stellarbridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** EVE's blade and drone on the client, which sees her live pose; each hit goes to the server as a strike payload. */
final class AvatarWeapons {
    private AvatarWeapons() { }

    /** Render thread: EVE's displayed blade, swept against the client's entities each frame while it swings. */
    static final class Blade {
        private final Vector3d hilt = new Vector3d(), tip = new Vector3d(), lastHilt = new Vector3d(), lastTip = new Vector3d();
        private boolean known;
        private double chargedUntil = Double.NEGATIVE_INFINITY;
        private final Map<Integer, Double> struck = new HashMap<>(); // Entity id to its last strike time.
        private final Set<Integer> chargeStruck = new HashSet<>(); // Entities the current charged activation struck.

        void update(AvatarRenderer renderer, List<AvatarStream.Effect> effects, double seconds) {
            var client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || !renderer.blade(hilt, tip)) { known = false; return; }
            AvatarTrail.Style style = null;
            for (AvatarStream.Effect effect : effects) {
                if (effect.name().contains("HoldBurst")) {
                    if (seconds >= chargedUntil) chargeStruck.clear(); // A new activation.
                    chargedUntil = seconds + AvatarCombat.CHARGE_HOLD;
                }
                AvatarTrail.Style playing = AvatarTrail.Style.of(effect.name());
                if (playing != null) style = playing;
            }
            struck.values().removeIf(time -> seconds - time > AvatarCombat.REHIT);
            if (known && renderer.trailStrength() > 0.5) {
                boolean charged = seconds < chargedUntil;
                Box sweep = new Box(new Vec3d(hilt.x, hilt.y, hilt.z), new Vec3d(tip.x, tip.y, tip.z))
                        .union(new Box(new Vec3d(lastHilt.x, lastHilt.y, lastHilt.z), new Vec3d(lastTip.x, lastTip.y, lastTip.z)))
                        .expand(AvatarCombat.BLADE_RADIUS);
                for (Entity entity : client.world.getOtherEntities(client.player, sweep, e -> AvatarCombat.strikable(e, client.player))) {
                    if (struck.containsKey(entity.getId()) || !AvatarCombat.sweeps(entity.getBoundingBox().expand(AvatarCombat.BLADE_RADIUS), lastHilt, lastTip, hilt, tip)) continue;
                    struck.put(entity.getId(), seconds);
                    StellarBridge.strike(entity.getId(), style, charged, charged && chargeStruck.add(entity.getId()), null);
                }
            }
            lastHilt.set(hilt);
            lastTip.set(tip);
            known = true;
        }
    }

    /** Where a shot leaves: the drone or EVE's hand, at an absolute Minecraft position. */
    record Muzzle(String from, Vec3d at) { }

    /**
     * After palettes with the same placement: the drone's bullet socket while a drone part is drawn, else EVE's right
     * hand, or null without either.
     */
    static Muzzle muzzle(AvatarModel model, List<AvatarRecording.Component> components, boolean[] drawn, AvatarRecording.Pose pose,
                         Matrix4d placement, Vec3d camera) {
        Vector3d at = new Vector3d();
        for (boolean drone : new boolean[] {true, false})
            for (int p = 0; p < model.parts.size(); p++) {
                var part = model.parts.get(p);
                if (!drawn[part.component()] || components.get(part.component()).name().startsWith(AvatarStream.OPTIONAL) != drone) continue;
                var bones = part.mesh().bones;
                for (int k = 0; k < bones.length; k++)
                    if (bones[k].name().equals(drone ? AvatarCombat.DRONE_MUZZLE : AvatarCombat.HAND)) {
                        model.point(pose, placement, p, k, drone ? AvatarCombat.DRONE_SOCKET : new Vector3d(), at);
                        return new Muzzle(drone ? "drone" : "hand", new Vec3d(at.x + camera.x, at.y + camera.y, at.z + camera.z));
                    }
            }
        return null;
    }

    /**
     * Render thread: EVE's drone shots, counted by the adapter as Stellar Blade fires them. Each flashes at the muzzle and
     * traces to where Minecraft's crosshair aims from her eyes (the first block or strikable entity within RANGE), and
     * strikes that entity through her blade's server path.
     */
    static final class Gun {
        private static final int LOGGED = 20; // ponytail: only the first shots are logged; the status line keeps counting.
        private static final double TRACER_STEP = 0.4; // Blocks between the tracer's sparks.
        private long seen = -1; // The adapter's count last seen.
        private int fired, hits;

        /**
         * Each frame with the adapter's count: fires its new shots while EVE is the bridged player from muzzle (asked only
         * then; null fires from her eyes), else only notes the count.
         */
        void update(long counter, boolean bridged, java.util.function.Supplier<Muzzle> muzzle) {
            int count = AvatarCombat.shots(seen, counter);
            seen = counter;
            var client = MinecraftClient.getInstance();
            if (count == 0 || !bridged || client.player == null || client.world == null) return;
            Muzzle from = muzzle.get();
            for (int i = 0; i < count; i++) fire(client, from);
        }

        private void fire(MinecraftClient client, Muzzle muzzle) {
            var player = client.player;
            Vec3d eyes = player.getEyePos(), end = eyes.add(player.getRotationVec(1).multiply(AvatarCombat.RANGE));
            var block = client.world.raycast(new RaycastContext(eyes, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
            if (block.getType() != HitResult.Type.MISS) end = block.getPos();
            List<Entity> entities = client.world.getOtherEntities(player, new Box(eyes, end).expand(1), e -> AvatarCombat.strikable(e, player));
            List<Box> boxes = entities.stream().map(e -> e.getBoundingBox().expand(e.getTargetingMargin())).toList();
            int hit = AvatarCombat.firstHit(eyes, end, boxes);
            if (hit >= 0) {
                Box box = boxes.get(hit);
                end = box.contains(eyes) ? eyes : box.raycast(eyes, end).orElse(end);
                StellarBridge.strike(entities.get(hit).getId(), null, false, false, end);
                hits++;
            }
            trace(client, muzzle == null ? eyes : muzzle.at(), end);
            if (++fired <= LOGGED) org.slf4j.LoggerFactory.getLogger("stellar_bridge").info("EVE drone shot {}: from the {}, {} at {} blocks",
                    fired, muzzle == null ? "eyes" : muzzle.from(), hit >= 0 ? "hit " + entities.get(hit).getType().getUntranslatedName()
                            : block.getType() != HitResult.Type.MISS ? "a block" : "nothing", String.format("%.1f", eyes.distanceTo(end)));
        }

        /** A muzzle flash of sparks, then a spark every TRACER_STEP blocks to the end, shown however far as fireworks are. */
        private static void trace(MinecraftClient client, Vec3d from, Vec3d to) {
            var world = client.world;
            var random = world.getRandom();
            for (int i = 0; i < 6; i++)
                world.addImportantParticle(ParticleTypes.ELECTRIC_SPARK, from.x, from.y, from.z,
                        random.nextGaussian() * 0.08, random.nextGaussian() * 0.08, random.nextGaussian() * 0.08);
            Vec3d path = to.subtract(from);
            double length = path.length();
            for (double d = TRACER_STEP; d < length; d += TRACER_STEP) {
                Vec3d point = from.add(path.multiply(d / length));
                world.addImportantParticle(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 0, 0, 0);
            }
        }

        String status(long counted) {
            return String.format(" Drone shots: %d counted, %d fired here, %d hits.", counted, fired, hits);
        }
    }
}

package org.stellarbridge;

import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * The network contract between the bridge's server and client halves (protocol/README.md): PROTOCOL, its payloads with
 * bounded codecs, and the version handshake in the configuration phase. A client without the mod never receives hello,
 * joins as a vanilla player and sees EVE players as vanilla models; a modded client of another protocol is disconnected
 * with both versions named. Timestamps in client payloads are the client's own clocks: the server compares them only with
 * other stamps from the same client, never with its own clock.
 */
public final class BridgeNet {
    /** Raised on any payload change. hello keeps its layout in every version, so any two versions can compare. */
    static final int PROTOCOL = 1;
    static final int TEXT = 65536, NAME = 256; // Characters: an EVE packet (BridgeFiles' 64 KiB) and a version, dimension or message.
    private static final Set<UUID> greeted = ConcurrentHashMap.newKeySet(); // Players whose client answered hello in this protocol.
    private static String version = "unknown";

    private BridgeNet() { }

    /** Both sides, from the main entrypoint: payload types, the server's handshake task and its answer. */
    static void register() {
        version = FabricLoader.getInstance().getModContainer("stellar_bridge")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        PayloadTypeRegistry.configurationS2C().register(Hello.ID, Hello.CODEC);
        PayloadTypeRegistry.configurationC2S().register(Hello.ID, Hello.CODEC);
        PayloadTypeRegistry.playS2C().register(Link.ID, Link.CODEC);
        PayloadTypeRegistry.playC2S().register(Eve.ID, Eve.CODEC);
        PayloadTypeRegistry.playS2C().register(Terrain.ID, Terrain.CODEC);
        PayloadTypeRegistry.playS2C().register(Mobs.ID, Mobs.CODEC);
        PayloadTypeRegistry.playC2S().register(Strike.ID, Strike.CODEC);
        PayloadTypeRegistry.playC2S().register(Defense.ID, Defense.CODEC);
        PayloadTypeRegistry.playC2S().register(ItemUse.ID, ItemUse.CODEC);
        PayloadTypeRegistry.playC2S().register(Status.ID, Status.CODEC);
        PayloadTypeRegistry.playS2C().register(Verdict.ID, Verdict.CODEC);
        PayloadTypeRegistry.playS2C().register(Feedback.ID, Feedback.CODEC);
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (ServerConfigurationNetworking.canSend(handler, Hello.ID)) handler.addTask(new HelloTask());
        });
        ServerConfigurationNetworking.registerGlobalReceiver(Hello.ID, (hello, context) -> {
            String refused = mismatch(hello);
            if (refused != null) {
                context.networkHandler().disconnect(Text.literal(refused));
                return;
            }
            greeted.add(context.networkHandler().getDebugProfile().getId());
            context.networkHandler().completeTask(HelloTask.KEY);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> greeted.remove(handler.player.getUuid()));
    }

    /** This mod's version, for hello. */
    static String version() { return version; }

    /** Whether the player's client completed the handshake (it runs this protocol). */
    static boolean greeted(ServerPlayerEntity player) { return greeted.contains(player.getUuid()); }

    /** The disconnect message for a client's hello of another protocol, or null when it matches. */
    static String mismatch(Hello client) {
        return client.protocol == PROTOCOL ? null : "Stellar Bridge protocol mismatch: this server runs protocol " + PROTOCOL
                + " (mod " + version + "), your client protocol " + client.protocol + " (mod " + client.version
                + "). Install Stellar Bridge " + version + " to join, or join without the mod.";
    }

    private record HelloTask() implements ServerPlayerConfigurationTask {
        static final Key KEY = new Key("stellar_bridge:hello");
        @Override public void sendPacket(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking.createS2CPacket(new Hello(PROTOCOL, version)));
        }
        @Override public Key getKey() { return KEY; }
    }

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String name) {
        return new CustomPayload.Id<>(Identifier.of("stellar_bridge", name)); // CustomPayload.id would use minecraft:.
    }

    private static DecoderException refused(String what) { return new DecoderException("Stellar Bridge payload refused: " + what); }

    private static void writeBox(PacketByteBuf buf, BridgeProtocol.Box box) {
        buf.writeDouble(box.minX()).writeDouble(box.minY()).writeDouble(box.minZ());
        buf.writeDouble(box.maxX()).writeDouble(box.maxY()).writeDouble(box.maxZ());
    }

    private static BridgeProtocol.Box readBox(PacketByteBuf buf) {
        return new BridgeProtocol.Box(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private static void writeBoxes(PacketByteBuf buf, List<BridgeProtocol.Box> boxes) {
        buf.writeVarInt(boxes.size());
        boxes.forEach(box -> writeBox(buf, box));
    }

    private static List<BridgeProtocol.Box> readBoxes(PacketByteBuf buf, int max) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) throw refused(count + " boxes");
        List<BridgeProtocol.Box> boxes = new ArrayList<>(count);
        for (int k = 0; k < count; k++) boxes.add(readBox(buf));
        return List.copyOf(boxes);
    }

    private static byte bounded(PacketByteBuf buf, int count, String what) {
        byte value = buf.readByte();
        if (value < 0 || value >= count) throw refused(what + " " + value);
        return value;
    }

    /** Configuration, both ways: the server's protocol and mod version, then the client's. */
    record Hello(int protocol, String version) implements CustomPayload {
        static final Id<Hello> ID = id("hello");
        static final PacketCodec<PacketByteBuf, Hello> CODEC = PacketCodec.of(
                (hello, buf) -> buf.writeVarInt(hello.protocol).writeString(hello.version, NAME),
                buf -> new Hello(buf.readVarInt(), buf.readString(NAME)));
        @Override public Id<Hello> getId() { return ID; }
    }

    /** S2C: the player's link: the session (empty: none, with the reason in message) and its anchor. */
    record Link(String session, Vec3d origin, String dimension, String message) implements CustomPayload {
        static final Id<Link> ID = id("link");
        static final PacketCodec<PacketByteBuf, Link> CODEC = PacketCodec.of((link, buf) -> {
            buf.writeString(link.session, 32);
            buf.writeDouble(link.origin.x).writeDouble(link.origin.y).writeDouble(link.origin.z);
            buf.writeString(link.dimension, NAME).writeString(link.message, NAME);
        }, buf -> new Link(buf.readString(32), new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readString(NAME), buf.readString(NAME)));
        @Override public Id<Link> getId() { return ID; }
    }

    /** C2S: an eve.N packet as Lua wrote it, and the client's wall clock (milliseconds) when it sent it. */
    record Eve(String text, long sent) implements CustomPayload {
        static final Id<Eve> ID = id("eve");
        static final PacketCodec<PacketByteBuf, Eve> CODEC = PacketCodec.of(
                (eve, buf) -> buf.writeString(eve.text, TEXT).writeLong(eve.sent),
                buf -> new Eve(buf.readString(TEXT), buf.readLong()));
        @Override public Id<Eve> getId() { return ID; }
    }

    /** S2C: a terrain snapshot; its time is not sent (0), the client stamps each terrain.txt with its own clock. */
    record Terrain(BridgeProtocol.Terrain snapshot) implements CustomPayload {
        static final Id<Terrain> ID = id("terrain");
        static final PacketCodec<PacketByteBuf, Terrain> CODEC = PacketCodec.of((terrain, buf) -> {
            var t = terrain.snapshot;
            buf.writeString(t.session(), 32).writeVarInt(t.seq()).writeString(t.dimension(), NAME);
            buf.writeDouble(t.originX()).writeDouble(t.originY()).writeDouble(t.originZ());
            writeBox(buf, t.bounds());
            writeBoxes(buf, t.boxes());
        }, buf -> new Terrain(new BridgeProtocol.Terrain(buf.readString(32), buf.readVarInt(), 0, buf.readString(NAME),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), readBox(buf), readBoxes(buf, BridgeProtocol.MAX_BOXES))));
        @Override public Id<Terrain> getId() { return ID; }
    }

    /** S2C: the mob walls, relative to the origin, nearest first. */
    record Mobs(List<BridgeProtocol.Box> boxes) implements CustomPayload {
        static final Id<Mobs> ID = id("mobs");
        static final PacketCodec<PacketByteBuf, Mobs> CODEC = PacketCodec.of(
                (mobs, buf) -> writeBoxes(buf, mobs.boxes), buf -> new Mobs(readBoxes(buf, BridgeProtocol.MOBS)));
        @Override public Id<Mobs> getId() { return ID; }
    }

    /**
     * C2S: one strike of EVE's blade (its trail style) or a drone shot (with the point it hit), on an entity the client
     * saw; the server computes the damage from the style.
     */
    record Strike(int entity, byte style, byte flags, Vec3d hit) implements CustomPayload {
        static final byte SWING = 0, LIGHT = 1, STRONG = 2, THRUST = 3, SHOT = 4;
        static final byte CHARGED = 1, FIRST = 2, QUIET = 4;
        static final Id<Strike> ID = id("strike");
        static final PacketCodec<PacketByteBuf, Strike> CODEC = PacketCodec.of((strike, buf) -> {
            buf.writeVarInt(strike.entity).writeByte(strike.style).writeByte(strike.flags);
            if (strike.style == SHOT) buf.writeDouble(strike.hit.x).writeDouble(strike.hit.y).writeDouble(strike.hit.z);
        }, buf -> {
            int entity = buf.readVarInt();
            byte style = bounded(buf, SHOT + 1, "strike style"), flags = buf.readByte();
            if ((flags & ~(CHARGED | FIRST | QUIET)) != 0) throw refused("strike flags " + flags);
            return new Strike(entity, style, flags, style == SHOT ? new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null);
        });
        @Override public Id<Strike> getId() { return ID; }
    }

    /**
     * C2S: a defense edge, when it happened (at) and when the client sent it (sent), both on the client's System.nanoTime,
     * so the server can place it before its arrival.
     */
    record Defense(byte edge, long at, long sent) implements CustomPayload {
        static final byte DODGE = 0, GUARD_DOWN = 1, GUARD_UP = 2;
        static final Id<Defense> ID = id("defense");
        static final PacketCodec<PacketByteBuf, Defense> CODEC = PacketCodec.of(
                (defense, buf) -> buf.writeByte(defense.edge).writeLong(defense.at).writeLong(defense.sent),
                buf -> new Defense(bounded(buf, GUARD_UP + 1, "defense edge"), buf.readLong(), buf.readLong()));
        @Override public Id<Defense> getId() { return ID; }
    }

    /** C2S: one use of a combat consumable (AvatarItems.SHOCK, PULSE, MINE, SONIC). */
    record ItemUse(byte kind) implements CustomPayload {
        static final Id<ItemUse> ID = id("item");
        static final PacketCodec<PacketByteBuf, ItemUse> CODEC = PacketCodec.of(
                (use, buf) -> buf.writeByte(use.kind), buf -> new ItemUse(bounded(buf, AvatarItems.USES.length, "item kind")));
        @Override public Id<ItemUse> getId() { return ID; }
    }

    /** C2S, on change: EVE's stats (null without them) and the adapter's heal count (-1: none, else a u32). */
    record Status(AvatarStats.Values stats, long heals) implements CustomPayload {
        static final Id<Status> ID = id("status");
        static final PacketCodec<PacketByteBuf, Status> CODEC = PacketCodec.of((status, buf) -> {
            var s = status.stats;
            buf.writeBoolean(s != null);
            if (s != null) for (float v : new float[] {s.maxHp(), s.shieldBlocks(), s.attack(), s.critPercent(), s.critRate(),
                    s.melee(), s.ranged(), s.finalHp()}) buf.writeFloat(v);
            buf.writeLong(status.heals);
        }, buf -> {
            float[] s = null;
            if (buf.readBoolean()) {
                s = new float[8];
                for (int k = 0; k < s.length; k++) if (!Float.isFinite(s[k] = buf.readFloat())) throw refused("stat " + s[k]);
            }
            long heals = buf.readLong();
            if (heals < -1 || heals > 0xFFFFFFFFL) throw refused("heal count " + heals);
            return new Status(AvatarStats.Values.of(s), heals);
        });
        @Override public Id<Status> getId() { return ID; }
    }

    /** S2C, to the defender: a verdict Stellar Blade answers (BridgeProtocol.COMBAT_EVENTS) and the attacker's yaw (NaN: none). */
    record Verdict(byte event, float yaw) implements CustomPayload {
        static final Id<Verdict> ID = id("verdict");
        static final PacketCodec<PacketByteBuf, Verdict> CODEC = PacketCodec.of(
                (verdict, buf) -> buf.writeByte(verdict.event).writeFloat(verdict.yaw),
                buf -> new Verdict(bounded(buf, BridgeProtocol.COMBAT_EVENTS.size(), "verdict"), buf.readFloat()));
        @Override public Id<Verdict> getId() { return ID; }
    }

    /** S2C, on change: the health share and heal count the server applied, and Beta and Burst earned (cumulative, wrapping). */
    record Feedback(float share, int acknowledged, int beta, int burst) implements CustomPayload {
        static final Id<Feedback> ID = id("feedback");
        static final PacketCodec<PacketByteBuf, Feedback> CODEC = PacketCodec.of(
                (feedback, buf) -> buf.writeFloat(feedback.share).writeInt(feedback.acknowledged).writeInt(feedback.beta).writeInt(feedback.burst),
                buf -> new Feedback(buf.readFloat(), buf.readInt(), buf.readInt(), buf.readInt()));
        @Override public Id<Feedback> getId() { return ID; }
    }
}

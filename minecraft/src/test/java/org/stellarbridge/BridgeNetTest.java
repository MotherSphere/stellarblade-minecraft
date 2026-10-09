package org.stellarbridge;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.Vec3d;

/** The network payloads: round trips, refused hostile sizes and values, and the handshake's mismatch message. */
public final class BridgeNetTest {
    public static void main(String[] args) {
        BridgeProtocol.Box box = new BridgeProtocol.Box(-1.5, 0, 2, 3, 4.25, 5);
        List<CustomPayload> payloads = List.of(
                new BridgeNet.Hello(BridgeNet.PROTOCOL, "0.1.0"),
                new BridgeNet.Link("a".repeat(32), new Vec3d(10.5, 64, -3.5), "minecraft:overworld", ""),
                new BridgeNet.Link("", Vec3d.ZERO, "", "Stand on a full block floor before starting the bridge."),
                new BridgeNet.Eve("SBMC 2 EVE\n" + "x".repeat(1000), 1_760_000_000_123L),
                new BridgeNet.Terrain(new BridgeProtocol.Terrain("b".repeat(32), 7, 0, "minecraft:the_nether", 0.5, 70, -0.5,
                        new BridgeProtocol.Box(-20, -8, -20, 20, 16, 20), java.util.Collections.nCopies(BridgeProtocol.MAX_BOXES, box))),
                new BridgeNet.Mobs(List.of(box, box)),
                new BridgeNet.Strike(42, BridgeNet.Strike.STRONG, (byte) (BridgeNet.Strike.CHARGED | BridgeNet.Strike.FIRST), null),
                new BridgeNet.Strike(7, BridgeNet.Strike.SHOT, (byte) 0, new Vec3d(1.25, 65.5, -30)),
                new BridgeNet.Defense(BridgeNet.Defense.GUARD_DOWN, -5_000_000_000L, 123_456_789L),
                new BridgeNet.ItemUse((byte) AvatarItems.SONIC),
                new BridgeNet.Status(new AvatarStats.Values(4000, 3, 900, 25, 1.5f, 0.1f, 0.2f, -0.3f), 0xFFFFFFFFL),
                new BridgeNet.Status(null, -1),
                new BridgeNet.Verdict((byte) BridgeProtocol.COMBAT_EVENTS.indexOf("perfect_dodge"), 135.5f),
                new BridgeNet.Verdict((byte) 0, Float.NaN),
                new BridgeNet.Feedback(0.75f, 12, -5, Integer.MAX_VALUE));
        java.util.Set<net.minecraft.util.Identifier> ids = new java.util.HashSet<>();
        for (CustomPayload payload : payloads) {
            assert payload.getId().id().getNamespace().equals("stellar_bridge") : payload;
            ids.add(payload.getId().id());
            PacketByteBuf buf = buffer();
            codec(payload).encode(buf, payload);
            Object back = codec(payload).decode(buf);
            assert buf.readableBytes() == 0 : "Everything written is read: " + payload;
            assert back.toString().equals(payload.toString()) : back + " != " + payload; // NaN-safe record comparison.
        }
        assert ids.size() == 11 : "One channel per payload kind, hello both ways: " + ids;
        int bytes = encoded(new BridgeNet.Strike(42, BridgeNet.Strike.LIGHT, (byte) 0, null)).readableBytes();
        assert bytes == 3 : "A melee strike is 3 bytes, not " + bytes;

        // Hostile values never decode: counts, lengths, enums and flags beyond their bounds, non-finite stats.
        refused(BridgeNet.Mobs.CODEC, buf -> writeBoxes(buf, BridgeProtocol.MOBS + 1, box));
        refused(BridgeNet.Mobs.CODEC, buf -> buf.writeVarInt(-1));
        refused(BridgeNet.Terrain.CODEC, buf -> {
            buf.writeString("c".repeat(32), 32).writeVarInt(1).writeString("minecraft:overworld");
            for (int k = 0; k < 9; k++) buf.writeDouble(k); // Origin and bounds.
            buf.writeVarInt(BridgeProtocol.MAX_BOXES + 1);
        });
        refused(BridgeNet.Link.CODEC, buf -> buf.writeString("d".repeat(33)));
        refused(BridgeNet.Hello.CODEC, buf -> buf.writeVarInt(1).writeString("v".repeat(BridgeNet.NAME + 1)));
        refused(BridgeNet.Eve.CODEC, buf -> buf.writeString("e".repeat(BridgeNet.TEXT + 1), BridgeNet.TEXT + 1).writeLong(0));
        refused(BridgeNet.Strike.CODEC, buf -> buf.writeVarInt(1).writeByte(5).writeByte(0));
        refused(BridgeNet.Strike.CODEC, buf -> buf.writeVarInt(1).writeByte(-1).writeByte(0));
        refused(BridgeNet.Strike.CODEC, buf -> buf.writeVarInt(1).writeByte(BridgeNet.Strike.LIGHT).writeByte(8));
        refused(BridgeNet.Strike.CODEC, buf -> buf.writeVarInt(1).writeByte(BridgeNet.Strike.SHOT).writeByte(0)); // No hit point.
        refused(BridgeNet.Defense.CODEC, buf -> buf.writeByte(3).writeLong(0).writeLong(0));
        refused(BridgeNet.ItemUse.CODEC, buf -> buf.writeByte(AvatarItems.USES.length));
        refused(BridgeNet.Verdict.CODEC, buf -> buf.writeByte(BridgeProtocol.COMBAT_EVENTS.size()).writeFloat(0));
        refused(BridgeNet.Status.CODEC, buf -> buf.writeBoolean(true).writeFloat(1).writeFloat(Float.NaN));
        refused(BridgeNet.Status.CODEC, buf -> buf.writeBoolean(true).writeFloat(Float.POSITIVE_INFINITY));
        refused(BridgeNet.Status.CODEC, buf -> buf.writeBoolean(false).writeLong(0x100000000L));
        refused(BridgeNet.Status.CODEC, buf -> buf.writeBoolean(false).writeLong(-2));

        // The handshake: the same protocol passes, another names both versions and the way in.
        assert BridgeNet.mismatch(new BridgeNet.Hello(BridgeNet.PROTOCOL, "anything")) == null;
        String message = BridgeNet.mismatch(new BridgeNet.Hello(BridgeNet.PROTOCOL + 1, "9.9.9"));
        assert message != null && message.contains("protocol " + BridgeNet.PROTOCOL) && message.contains("protocol " + (BridgeNet.PROTOCOL + 1))
                && message.contains("9.9.9") && message.contains("without the mod") : message;
        System.out.println("Bridge network tests passed: 11 payload channels round trip, hostile sizes and values refused, protocol mismatch message");
    }

    private static PacketByteBuf buffer() { return new PacketByteBuf(Unpooled.buffer()); }

    @SuppressWarnings("unchecked")
    private static PacketCodec<PacketByteBuf, CustomPayload> codec(CustomPayload payload) {
        try {
            return (PacketCodec<PacketByteBuf, CustomPayload>) payload.getClass().getDeclaredField("CODEC").get(null);
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError(payload.getClass() + " has no CODEC", missing);
        }
    }

    private static PacketByteBuf encoded(CustomPayload payload) {
        PacketByteBuf buf = buffer();
        codec(payload).encode(buf, payload);
        return buf;
    }

    private static PacketByteBuf writeBoxes(PacketByteBuf buf, int count, BridgeProtocol.Box box) {
        buf.writeVarInt(count);
        for (int k = 0; k < count; k++)
            buf.writeDouble(box.minX()).writeDouble(box.minY()).writeDouble(box.minZ()).writeDouble(box.maxX()).writeDouble(box.maxY()).writeDouble(box.maxZ());
        return buf;
    }

    private static void refused(PacketCodec<PacketByteBuf, ?> codec, Consumer<PacketByteBuf> hostile) {
        PacketByteBuf buf = buffer();
        hostile.accept(buf);
        try {
            Object decoded = codec.decode(buf);
            throw new AssertionError("Decoded a hostile payload: " + decoded);
        } catch (RuntimeException expected) {
            // DecoderException for a refused value or length; IndexOutOfBoundsException for a missing field.
        }
    }
}

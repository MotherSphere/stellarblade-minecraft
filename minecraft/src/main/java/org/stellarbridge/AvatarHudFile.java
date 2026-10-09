package org.stellarbridge;

import java.io.IOException;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/** The client half of AvatarItems: Lua's HUD file read each client tick while EVE replaces the player; each new use goes to the server. */
final class AvatarHudFile {
    private static BridgeFiles files; // Render thread, from here to state.
    private static AvatarItems.Hud latest; // The link's newest fresh publication while EVE replaces the player.
    private static int[] seen; // Its uses as last read, for that session.
    private static String session, state = "missing";
    private static final int[] sent = new int[AvatarItems.USES.length]; // Uses sent to the server, per kind.

    private AvatarHudFile() { }

    static void initialize() { ClientTickEvents.END_CLIENT_TICK.register(AvatarHudFile::read); }

    private static void read(MinecraftClient client) {
        var link = StellarBridge.renderingContext();
        if (link == null || !AvatarView.replaces(client.player)) { latest = null; seen = null; state = "missing"; return; }
        AvatarItems.Hud hud = null;
        try {
            if (files == null) files = new BridgeFiles(AvatarView.bridgeDirectory());
            hud = AvatarItems.select(files.read("eve-hud.0"), files.read("eve-hud.1"), link.session());
        } catch (IOException unreadable) {
            // As missing: the slots hide.
        }
        boolean current = hud != null && AvatarItems.fresh(hud.time(), System.currentTimeMillis());
        state = hud == null ? "missing" : current ? "fresh" : "stale";
        latest = current ? hud : null;
        if (!current) return;
        if (!link.session().equals(session)) { session = link.session(); seen = null; }
        int[] steps = AvatarItems.used(seen, hud.uses());
        seen = hud.uses();
        for (int k = 0; ClientPlayNetworking.canSend(BridgeNet.ItemUse.ID) && k < steps.length; k++)
            for (int n = 0; n < steps[k]; n++) {
                ClientPlayNetworking.send(new BridgeNet.ItemUse((byte) k));
                sent[k]++;
            }
    }

    /** Render thread: the newest fresh publication while EVE replaces the player, or null. */
    static AvatarItems.Hud latest() { return latest; }

    /** Render thread. */
    static String status() { return AvatarItems.status(state, sent); }
}

package org.stellarbridge.mixin;

import net.minecraft.client.MinecraftClient;
import org.objectweb.asm.Opcodes;
import org.stellarbridge.StellarBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
abstract class BridgePauseMixin {
    @Shadow private boolean paused;

    @Redirect(method = "render(Z)V", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/MinecraftClient;paused:Z", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void stellarKeepBridgeRunning(MinecraftClient client, boolean requestedPause) {
        // Modify the single assignment before both client tick timing and IntegratedServer observe it.
        boolean running = requestedPause && StellarBridge.keepsWorldRunning(client);
        StellarBridge.pauseOverridden(running);
        paused = requestedPause && !running;
    }
}

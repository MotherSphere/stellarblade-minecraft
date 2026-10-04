package org.stellarbridge.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.stellarbridge.StellarBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerMovementMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void stellarBridgeMovement(CallbackInfo callback) {
        if (StellarBridge.overrides((ClientPlayerEntity) (Object) this)) callback.cancel();
    }
}

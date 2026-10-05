package org.stellarbridge.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.stellarbridge.ControlBridge;
import org.stellarbridge.StellarBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerMovementMixin {
    @Shadow private int ticksToNextAutojump;

    @Inject(method = "tickMovement", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/input/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void stellarBridgeInput(CallbackInfo callback) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (StellarBridge.overrides(player)) {
            ControlBridge.suppressVanillaMovement(player.input);
            ticksToNextAutojump = 0;
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void stellarBridgeMovement(MovementType type, Vec3d displacement, CallbackInfo callback) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (StellarBridge.overrides(player)) {
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0;
            callback.cancel();
        }
    }
}

package org.stellarbridge.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.stellarbridge.ControlBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
abstract class ControlMouseMixin {
    @Inject(method = "onMouseButton", at = @At("TAIL"))
    private void stellarButton(long window, int button, int action, int modifiers, CallbackInfo callback) {
        ControlBridge.mouseButton(window, button, action);
    }
    @Redirect(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void stellarLook(ClientPlayerEntity player, double x, double y) {
        if (!ControlBridge.mouseLook(x, y)) player.changeLookDirection(x, y);
    }
}

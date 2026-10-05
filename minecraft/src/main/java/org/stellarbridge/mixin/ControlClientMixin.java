package org.stellarbridge.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Overlay;
import net.minecraft.client.gui.screen.Screen;
import org.stellarbridge.ControlBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
abstract class ControlClientMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void stellarScreen(Screen screen, CallbackInfo callback) { if (screen != null) ControlBridge.suspend(); }
    @Inject(method = "setOverlay", at = @At("HEAD"))
    private void stellarOverlay(Overlay overlay, CallbackInfo callback) { if (overlay != null) ControlBridge.suspend(); }
    @Inject(method = "onWindowFocusChanged", at = @At("HEAD"))
    private void stellarFocus(boolean focused, CallbackInfo callback) { if (!focused) ControlBridge.suspend(); }
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void stellarAttack(CallbackInfoReturnable<Boolean> callback) {
        if (ControlBridge.suppressAttack()) callback.setReturnValue(false);
    }
    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void stellarUse(CallbackInfo callback) { if (ControlBridge.suppressUse()) callback.cancel(); }
    @ModifyVariable(method = "handleBlockBreaking", at = @At("HEAD"), argsOnly = true)
    private boolean stellarBreaking(boolean breaking) { return breaking && !ControlBridge.suppressAttack(); }
}

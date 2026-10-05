package org.stellarbridge.mixin;

import net.minecraft.client.Keyboard;
import org.stellarbridge.ControlBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
abstract class ControlKeyboardMixin {
    @Inject(method = "onKey", at = @At("TAIL"))
    private void stellarInput(long window, int key, int scan, int action, int modifiers, CallbackInfo callback) {
        ControlBridge.keyboard(window, key, scan, action, modifiers);
    }
}

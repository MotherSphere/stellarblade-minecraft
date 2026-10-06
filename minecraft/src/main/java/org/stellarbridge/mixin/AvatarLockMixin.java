package org.stellarbridge.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.stellarbridge.AvatarLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
abstract class AvatarLockMixin {
    // EVE's lock-on target is outlined as a glowing entity is.
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void stellarLockOutline(Entity entity, CallbackInfoReturnable<Boolean> info) {
        if (AvatarLock.outlines(entity)) info.setReturnValue(true);
    }
}

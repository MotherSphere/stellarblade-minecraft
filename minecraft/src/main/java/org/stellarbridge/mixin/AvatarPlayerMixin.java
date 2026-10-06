package org.stellarbridge.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
abstract class AvatarPlayerMixin {
    // The live avatar is drawn at the bridged player position; skip the vanilla body there.
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stellarAvatarReplacesPlayer(E entity, Frustum frustum, double x, double y, double z,
                                                               CallbackInfoReturnable<Boolean> info) {
        if (AvatarView.replaces(entity)) info.setReturnValue(false);
    }
}

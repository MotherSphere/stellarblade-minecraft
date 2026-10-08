package org.stellarbridge.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
abstract class AvatarPlayerMixin {
    // The live avatar is drawn at the bridged player position; skip the vanilla body there, except in Iris's shadow pass,
    // where render below draws EVE's shadow instead.
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stellarAvatarReplacesPlayer(E entity, Frustum frustum, double x, double y, double z,
                                                               CallbackInfoReturnable<Boolean> info) {
        if (AvatarView.replaces(entity)) info.setReturnValue(AvatarView.castsShadow());
    }

    // Iris's shadow pass renders the player here, its shadow view on the stack: EVE casts the shadow instead.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stellarAvatarShadow(E entity, double x, double y, double z, float yaw, float tickDelta,
                                                       MatrixStack matrices, VertexConsumerProvider consumers, int light, CallbackInfo info) {
        if (!AvatarView.replaces(entity) || !AvatarView.castsShadow()) return;
        info.cancel();
        AvatarView.drawShadow(matrices.peek().getPositionMatrix());
    }
}

package org.stellarbridge.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
abstract class AvatarPlayerMixin {
    // This player's live avatar at the bridged position: skip the vanilla body (a player sees no name tag of their own),
    // except in Iris's shadow pass, where render below draws EVE's shadow instead.
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stellarAvatarReplacesPlayer(E entity, Frustum frustum, double x, double y, double z,
                                                               CallbackInfoReturnable<Boolean> info) {
        if (AvatarView.replaces(entity)) info.setReturnValue(AvatarView.castsShadow());
    }

    // An EVE is drawn for this player: in Iris's shadow pass, its shadow view on the stack, EVE casts the shadow instead;
    // otherwise (another player's EVE from this pack) only the vanilla body goes, its name tag stays. Every other player
    // keeps the vanilla model.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void stellarAvatarShadow(E entity, double x, double y, double z, float yaw, float tickDelta,
                                                       MatrixStack matrices, VertexConsumerProvider consumers, int light, CallbackInfo info) {
        if (!AvatarView.draws(entity)) return;
        if (AvatarView.castsShadow()) {
            info.cancel();
            AvatarView.drawShadow(entity, matrices.peek().getPositionMatrix());
            return;
        }
        if (AvatarView.replaces(entity)) return; // This player outside the world pass (the inventory's preview): vanilla.
        info.cancel();
        EntityRenderer<? super E> renderer = ((EntityRenderDispatcher) (Object) this).getRenderer(entity);
        EntityLabelInvoker label = (EntityLabelInvoker) (Object) renderer;
        if (!label.stellarHasLabel(entity)) return;
        Vec3d offset = renderer.getPositionOffset(entity, tickDelta);
        matrices.push();
        matrices.translate(x + offset.x, y + offset.y, z + offset.z); // As render places the entity's renderer.
        label.stellarRenderLabel(entity, entity.getDisplayName(), matrices, consumers, light, tickDelta);
        matrices.pop();
    }
}

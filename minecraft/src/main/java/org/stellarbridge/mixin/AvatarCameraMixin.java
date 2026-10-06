package org.stellarbridge.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
abstract class AvatarCameraMixin {
    @Shadow private Entity focusedEntity;
    @Shadow private float lastTickDelta;
    @Shadow protected abstract void setPos(double x, double y, double z);

    // Follow the displayed EVE (60 Hz poses, blended per frame) instead of the 20 Hz bridged player position;
    // the vanilla eye height and third-person offset still apply.
    @Redirect(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V"))
    private void stellarAvatarCamera(Camera camera, double x, double y, double z) {
        var feet = focusedEntity == net.minecraft.client.MinecraftClient.getInstance().player ? AvatarView.cameraFeet() : null;
        if (feet == null) { setPos(x, y, z); return; }
        double eye = y - MathHelper.lerp(lastTickDelta, focusedEntity.prevY, focusedEntity.getY());
        setPos(feet.x, feet.y + eye, feet.z);
    }
}

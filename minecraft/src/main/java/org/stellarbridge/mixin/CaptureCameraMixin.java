package org.stellarbridge.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.stellarbridge.CaptureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class CaptureCameraMixin {
    @Shadow protected abstract void setPos(double x, double y, double z);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow private boolean thirdPerson;

    @Inject(method = "update", at = @At("TAIL"))
    private void stellarCaptureCamera(BlockView world, Entity entity, boolean thirdPerson, boolean inverseView, float delta, CallbackInfo info) {
        var capture = CaptureView.camera();
        if (capture == null) return;
        setPos(capture.x(), capture.y(), capture.z());
        setRotation(capture.yaw(), capture.pitch());
        this.thirdPerson = false; // Hide the vanilla owner's body while retaining the captured third-person extrinsic.
    }
}

package org.stellarbridge.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import org.joml.Matrix4f;
import org.stellarbridge.CaptureView;
import org.stellarbridge.CaptureTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class CaptureGameRendererMixin {
    @Inject(method = "findCrosshairTarget", at = @At("HEAD"), cancellable = true)
    private void stellarCaptureTarget(Entity owner, double blockRange, double entityRange, float delta,
                                      CallbackInfoReturnable<HitResult> info) {
        var camera = CaptureView.targetCamera();
        if (camera != null && owner == MinecraftClient.getInstance().player)
            info.setReturnValue(CaptureTarget.find(owner, camera, blockRange, entityRange, delta));
    }

    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void stellarBeginCapture(RenderTickCounter ticks, CallbackInfo info) {
        CaptureView.beginFrame();
        org.stellarbridge.AvatarView.beginFrame(); // Before the camera update, which follows the displayed EVE.
    }

    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void stellarEndCapture(RenderTickCounter ticks, CallbackInfo info) { CaptureView.endFrame(); }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
    private void stellarCaptureFov(Camera camera, float delta, boolean changing, CallbackInfoReturnable<Double> info) {
        var capture = CaptureView.camera();
        if (capture != null) info.setReturnValue(capture.verticalFov());
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void stellarHideHand(Camera camera, float delta, Matrix4f matrix, CallbackInfo info) {
        if (CaptureView.camera() != null) info.cancel();
    }

    @Inject(method = {"bobView", "tiltViewWhenHurt"}, at = @At("HEAD"), cancellable = true)
    private void stellarStillProjection(MatrixStack matrices, float delta, CallbackInfo info) {
        if (CaptureView.camera() != null) info.cancel();
    }
}

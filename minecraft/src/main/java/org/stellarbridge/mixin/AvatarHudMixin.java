package org.stellarbridge.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class AvatarHudMixin {
    // While EVE replaces the player, Stellar Blade's health bar and gauges take the hearts' place. Yarn's names here are
    // swapped: lastHealth is the current health, health the lagging display value.
    @Inject(method = "renderHealthBar", at = @At("HEAD"), cancellable = true)
    private void stellarEveHealth(DrawContext context, PlayerEntity player, int x, int y, int lines, int regeneratingHeartIndex,
                                  float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo info) {
        if (AvatarView.drawsHud(context, player, x, y, lines, maxHealth, lastHealth, health, absorption, blinking)) info.cancel();
    }
}

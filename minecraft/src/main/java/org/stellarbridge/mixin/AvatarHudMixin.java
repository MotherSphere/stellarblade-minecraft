package org.stellarbridge.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.stellarbridge.AvatarView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
abstract class AvatarHudMixin {
    // While EVE replaces the player, Stellar Blade's HUD takes the hearts' place. Yarn's names here are swapped: lastHealth
    // is the current health, health the lagging display value.
    @Inject(method = "renderHealthBar", at = @At("HEAD"), cancellable = true)
    private void stellarEveHealth(DrawContext context, PlayerEntity player, int x, int y, int lines, int regeneratingHeartIndex,
                                  float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo info) {
        if (AvatarView.drawsHud(context, player, x, y, lines, maxHealth, lastHealth, health, absorption, blinking)) info.cancel();
    }

    private static final String ARMOR = "Lnet/minecraft/client/gui/hud/InGameHud;renderArmor(Lnet/minecraft/client/gui/DrawContext;"
            + "Lnet/minecraft/entity/player/PlayerEntity;IIII)V";
    // The heart row stays empty while EVE replaces the player: vanilla's armor row, at y = i - (rows - 1) * lines - 10, moves
    // down into it (i + 10 with one row).
    @ModifyArg(method = "renderStatusBars", at = @At(value = "INVOKE", target = ARMOR), index = 2)
    private int stellarArmorY(DrawContext context, PlayerEntity player, int i, int rows, int lines, int x) {
        return AvatarView.replaces(player) ? i + 10 : i;
    }
    @ModifyArg(method = "renderStatusBars", at = @At(value = "INVOKE", target = ARMOR), index = 3)
    private int stellarArmorRows(DrawContext context, PlayerEntity player, int i, int rows, int lines, int x) {
        return AvatarView.replaces(player) ? 1 : rows;
    }
}

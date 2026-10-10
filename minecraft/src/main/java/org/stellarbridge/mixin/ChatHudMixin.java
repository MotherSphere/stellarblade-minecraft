package org.stellarbridge.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.stellarbridge.AvatarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ChatHud.class)
abstract class ChatHudMixin {
    // While Stellar Blade's HUD fills the bottom-left corner, the chat's bottom edge (40 above the screen's bottom in its
    // render and in both click tests) rises above it. getWidth's 40s, the minimum width, stay.
    @ModifyConstant(method = "render", constant = @Constant(intValue = 40), allow = 1)
    private int stellarChatBottom(int bottom) { return bottom + AvatarHud.chatLift(); }

    @ModifyConstant(method = {"mouseClicked", "toChatLineY"}, constant = @Constant(doubleValue = 40.0), require = 2, allow = 2)
    private double stellarChatBottom(double bottom) { return bottom + AvatarHud.chatLift(); }
}

package org.stellarbridge.mixin;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// An entity renderer's name tag on its own, for a player whose body another player's EVE replaces (AvatarPlayerMixin).
// Public: Minecraft's renderer classes implement it.
@Mixin(EntityRenderer.class)
public interface EntityLabelInvoker {
    @Invoker("hasLabel")
    boolean stellarHasLabel(Entity entity);

    @Invoker("renderLabelIfPresent")
    void stellarRenderLabel(Entity entity, Text text, MatrixStack matrices, VertexConsumerProvider consumers, int light, float tickDelta);
}

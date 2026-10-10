package org.stellarbridge.mixin;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.stellarbridge.AvatarCombat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
abstract class AvatarDefenseMixin {
    // Every damage to the bridged player, from any mob or projectile, meets EVE's dodge and guard at the impact.
    @Inject(method = "damage", at = @At("HEAD"), cancellable = true)
    private void stellarEveDefends(DamageSource source, float amount, CallbackInfoReturnable<Boolean> info) {
        Boolean result = AvatarCombat.defend((ServerPlayerEntity) (Object) this, source, amount);
        if (result != null) info.setReturnValue(result);
    }
}

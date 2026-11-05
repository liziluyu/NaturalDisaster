package com.fst.naturaldisaster.mixin;

import com.fst.naturaldisaster.effect.SnowStormEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Changes only exposed players' native thaw step; actual powder snow retains vanilla behavior. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFreezingMixin {
    @ModifyArg(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setTicksFrozen(I)V", ordinal = 1), index = 0)
    private int naturaldisaster$slowFreezing(int vanillaTicks) {
        return (Object) this instanceof ServerPlayer player
                ? SnowStormEffects.frozenTicksAfterThaw(player, vanillaTicks) : vanillaTicks;
    }

    @WrapOperation(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean naturaldisaster$armorProtectsFromStorm(LivingEntity entity, DamageSource source, float amount,
                                                          Operation<Boolean> original) {
        if (source.is(DamageTypeTags.IS_FREEZING) && entity instanceof ServerPlayer player
                && SnowStormEffects.blocksStormFreezeDamage(player)) {
            return false;
        }
        return original.call(entity, source, amount);
    }
}

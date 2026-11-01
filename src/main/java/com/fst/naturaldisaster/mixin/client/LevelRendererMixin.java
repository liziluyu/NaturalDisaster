package com.fst.naturaldisaster.mixin.client;

import com.fst.naturaldisaster.client.ClientDisasterEffects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Client-only precipitation override for forced weather.
 *
 * <p>Vanilla {@code renderSnowAndRain} first skips a position when the biome reports no
 * precipitation (deserts, oceans, the Nether, ...), then asks the biome whether that position is
 * rain or snow based on its temperature and height. Both decisions are redirected here so a forced
 * RAIN/THUNDER/SNOW display is identical in every biome. With no forced weather the original biome
 * methods are called unchanged.
 *
 * <p>This mixin only touches the client renderer. It never modifies {@code Biome}, level data or
 * the integrated server, and it is listed in the {@code client} array of the mixin config so it is
 * not applied on a dedicated server.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /**
     * Keeps the per-position precipitation loop from being skipped in dry biomes while an override
     * is active. The original method is still consulted when nothing is forced.
     */
    @Redirect(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Biome;hasPrecipitation()Z"))
    private boolean naturaldisaster$hasForcedPrecipitation(Biome biome) {
        return ClientDisasterEffects.forcedPrecipitation() != null || biome.hasPrecipitation();
    }

    /**
     * Replaces the biome-derived rain/snow decision with the forced type while an override is
     * active. Stopping the override immediately restores vanilla per-biome rendering.
     */
    @Redirect(
            method = "renderSnowAndRain",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation naturaldisaster$getForcedPrecipitation(Biome biome, BlockPos pos) {
        Biome.Precipitation forced = ClientDisasterEffects.forcedPrecipitation();
        return forced != null ? forced : biome.getPrecipitationAt(pos);
    }
}

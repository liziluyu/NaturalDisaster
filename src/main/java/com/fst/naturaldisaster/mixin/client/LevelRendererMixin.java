package com.fst.naturaldisaster.mixin.client;

import com.fst.naturaldisaster.client.ClientDisasterEffects;
import com.fst.naturaldisaster.client.ClientDisasterVisuals;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    @Unique
    private boolean naturaldisaster$disasterPass;
    @Unique
    private float naturaldisaster$passIntensity = 1f;

    /** Cross-fade vanilla/debug precipitation and snow, including transitions during real rain. */
    @WrapMethod(method = "renderSnowAndRain")
    private void naturaldisaster$fadePrecipitation(LightTexture light, float partialTick,
                                                  double x, double y, double z, Operation<Void> original) {
        float alpha = ClientDisasterVisuals.intensity(partialTick);
        boolean previousPass = naturaldisaster$disasterPass;
        float previousIntensity = naturaldisaster$passIntensity;
        try {
            if (alpha < 1f) {
                naturaldisaster$disasterPass = false;
                naturaldisaster$passIntensity = 1f - alpha;
                original.call(light, partialTick, x, y, z);
            }
            if (alpha > 0f) {
                naturaldisaster$disasterPass = true;
                naturaldisaster$passIntensity = alpha;
                original.call(light, partialTick, x, y, z);
            }
        } finally {
            naturaldisaster$disasterPass = previousPass;
            naturaldisaster$passIntensity = previousIntensity;
        }
    }

    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float naturaldisaster$fadeDensity(ClientLevel level, float partialTick) {
        return naturaldisaster$disasterPass ? naturaldisaster$passIntensity
                : level.getRainLevel(partialTick) * naturaldisaster$passIntensity;
    }

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
        return naturaldisaster$disasterPass
                || ClientDisasterEffects.forcedPrecipitation() != null || biome.hasPrecipitation();
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
        if (naturaldisaster$disasterPass) {
            return Biome.Precipitation.SNOW;
        }
        Biome.Precipitation forced = ClientDisasterEffects.forcedPrecipitation();
        return forced != null ? forced : biome.getPrecipitationAt(pos);
    }
}

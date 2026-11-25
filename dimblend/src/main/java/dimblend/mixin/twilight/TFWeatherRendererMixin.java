package dimblend.mixin.twilight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dimblend.compat.TwilightBand;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import twilightforest.client.renderer.TFWeatherRenderer;

/**
 * TF 4.8.3345's locked-biome weather samples biomes at a hardcoded {@code y = 0}
 * ({@code TFWeatherRenderer.isNearLockedBiome} and {@code renderLockedBiome} both do
 * {@code pos.set(x, 0, z)}), and reuses that same {@code pos} as the acid-rain
 * ({@code BIG_RAIN}) light sample.
 *
 * <p>In {@code dimblend:rotating} the twilight delegate is wrapped by
 * {@code YShiftedBiomeSource} with {@code y_offset = +64}, so quart Y {@code 0} asks the inner
 * quart grid for {@code -16} (vanilla TF Y {@code -64}) and every column answers
 * {@code twilightforest:underground}. That biome has no restriction entry, so
 * {@code isNearLockedBiome} returns false for every position and no curse curtain (blizzard,
 * mosquitoes, ashes, dark stream, acid rain) has ever been drawn for a player standing on the
 * lifted terrain. Raising the sample Y alone would then draw the acid rain as dead-black
 * stripes, because {@code getLightColor} would be reading the solid rock at Y {@code 0}.
 *
 * <p>{@link TwilightBand#weatherSampleY} answers both: the camera's own block Y (never below
 * {@link TwilightBand#DEFAULT_Y_OFFSET}) for surface bands — light and biome come from the air
 * the camera is in — and the player's Y while they are inside a {@code twilightforest:underground}
 * cave, so caves stay as calm as the server {@code Enforcement} path treats them.
 *
 * <p>The curse curtains are deliberately <em>not</em> clipped against Sable carriage roofs: the
 * user picked "curse weather goes straight through the carriage" over per-column
 * {@code SubLevelRainColumn.height} queries (cheaper, and the look they asked for).
 */
@Mixin(value = TFWeatherRenderer.class, remap = false)
public abstract class TFWeatherRendererMixin {

    /** The {@code ±15} locked-biome probe in {@code isNearLockedBiome}. */
    @WrapOperation(
            method = "isNearLockedBiome",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;"),
            require = 1,
            allow = 1
    )
    private static BlockPos.MutableBlockPos dimblend$sampleProbeY(
            BlockPos.MutableBlockPos self,
            int x,
            int y,
            int z,
            Operation<BlockPos.MutableBlockPos> original,
            @Local(argsOnly = true) Level level,
            @Local(argsOnly = true) Entity viewEntity
    ) {
        if (!TwilightBand.isRotating(level)) {
            return original.call(self, x, y, z);
        }
        double cameraY = viewEntity == null ? TwilightBand.DEFAULT_Y_OFFSET : viewEntity.getY();
        return original.call(
                self, x, TwilightBand.weatherSampleY(level, viewEntity, cameraY), z);
    }

    /** The per-column biome/light sample in {@code renderLockedBiome}'s {@code (2*range+1)^2} sweep. */
    @WrapOperation(
            method = "renderLockedBiome",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;"),
            require = 1,
            allow = 1
    )
    private static BlockPos.MutableBlockPos dimblend$sampleColumnY(
            BlockPos.MutableBlockPos self,
            int x,
            int y,
            int z,
            Operation<BlockPos.MutableBlockPos> original,
            @Local(argsOnly = true) ClientLevel level,
            @Local(argsOnly = true) LocalPlayer player,
            @Local(argsOnly = true) Vec3 camera
    ) {
        if (!TwilightBand.isRotating(level)) {
            return original.call(self, x, y, z);
        }
        return original.call(self, x, TwilightBand.weatherSampleY(level, player, camera.y()), z);
    }
}

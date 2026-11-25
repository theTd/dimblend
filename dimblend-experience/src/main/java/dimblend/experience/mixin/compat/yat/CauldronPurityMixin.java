package dimblend.experience.mixin.compat.yat;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.CauldronPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 缺口 11：原版炼药锅"接雨"与"接钟乳石滴水"。
 *
 * <p>原版这两条都会把锅变成 {@code water_cauldron} 而不带任何纯度标记，于是 YAT 读出来就是
 * 默认 2（可接受）——雨水与滴水本该只有 1。这里在两个类的同名方法 TAIL 之后把
 * {@code BLOCK_PURITY} 并入 1（{@code min} 合并，锅里的水只会更脏）：</p>
 * <ul>
 *   <li>{@code CauldronBlock#handlePrecipitation} / {@code LayeredCauldronBlock#handlePrecipitation}；</li>
 *   <li>{@code CauldronBlock#receiveStalactiteDrip} / {@code LayeredCauldronBlock#receiveStalactiteDrip}
 *       （滴水源头是钟乳石尖，不是水方块，取不到真实纯度，按 1 处理）。</li>
 * </ul>
 *
 * <p>只有方块态带 {@code BLOCK_PURITY} 时才写（空锅没有该属性，见 {@link CauldronPurity}）。</p>
 */
@Mixin({CauldronBlock.class, LayeredCauldronBlock.class})
public abstract class CauldronPurityMixin {

    @Inject(method = "handlePrecipitation", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$rainPurity(BlockState state, Level level, BlockPos pos, Biome.Precipitation precipitation,
            CallbackInfo ci) {
        if (DirtyWaterCompat.purityAvailable()) {
            CauldronPurity.blend(level, pos, WaterSourcePurityRules.RAIN_PURITY);
        }
    }

    @Inject(method = "receiveStalactiteDrip", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$dripPurity(BlockState state, Level level, BlockPos pos, Fluid fluid, CallbackInfo ci) {
        if (DirtyWaterCompat.purityAvailable()) {
            CauldronPurity.blend(level, pos, WaterSourcePurityRules.RAIN_PURITY);
        }
    }
}

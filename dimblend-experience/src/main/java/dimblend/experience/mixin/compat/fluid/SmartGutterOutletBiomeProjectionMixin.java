package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.block.GutterOutlet.SmartGutterOutletBlockEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 集水器（Sable 结构上）按结构当前所在的世界位置判定降水类型，而不是按 plot 坐标。
 *
 * <p>结构上的方块存在远处的 plot 里，{@code worldPosition} 是 plot 坐标：plot 区块的群系是
 * 结构创建时烤进去的、之后不再更新，{@code Biome#getPrecipitationAt} 的温度还会用 plot 的
 * Y（约在建筑高度中点）修正海拔。于是结构已开到暖群系，雨天集水器仍按"创建时的雪地"收细雪。
 * 这里把 {@code getBiome} 与 {@code getPrecipitationAt} 的位置参数投影到世界坐标
 *（未装 Sable / 非结构方块原样返回）。{@code canSeeSky} 保持 plot 局部判定，遮顶仍按结构自身。</p>
 *
 * <p>Sable 本体同口径的 {@code BiomeManager} 投影若已生效，{@code getBiome} 这一处是幂等的重复
 * 投影（世界坐标不在 plot 内）；保留它是为了不依赖 Sable 构建版本。基线：Create: Fluid 2.0.1
 * 字节码，经 javap 核实。</p>
 */
@Mixin(SmartGutterOutletBlockEntity.class)
public abstract class SmartGutterOutletBiomeProjectionMixin {

    @WrapOperation(
            method = "handlePrecipitationCollectionFiltered",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            remap = false)
    private Holder<Biome> dimblend$projectBiomeLookup(Level level, BlockPos pos, Operation<Holder<Biome>> original) {
        return original.call(level, SableWorldPosition.projectBlock(level, pos));
    }

    @WrapOperation(
            method = "handlePrecipitationCollectionFiltered",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"),
            remap = false)
    private Biome.Precipitation dimblend$projectPrecipitation(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
        // Biome 实例方法拿不到 level，取本方块实体所在的 level
        return original.call(biome, SableWorldPosition.projectBlock(((BlockEntity) (Object) this).getLevel(), pos));
    }
}

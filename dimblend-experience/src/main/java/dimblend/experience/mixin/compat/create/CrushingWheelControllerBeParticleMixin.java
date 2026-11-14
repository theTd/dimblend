package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.compat.create.CrushingParticleMath;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 标记 Sable 子层级上粉碎轮粉碎物品时的方块/物品碎屑粒子，并把 plot 坐标系里被 {@code float}
 * 吞掉的抖动还原成 {@code double} 口径。
 *
 * <p>Create 6.0.10 的 {@code spawnParticles} 字节码（{@code 89..116}）同样是
 * {@code worldPosition.getX() → i2f → r.nextFloat() → fadd → f2d}，四颗碎屑在 plot 坐标
 * （{@code ≥ 2^24}，ULP = 2.0）下会被压到同一个整数格角上：抖动全丢、奇数坐标再整体挪 1 格，
 * 表现为「磨轮旁边固定位置冒碎屑，挪一格才换位置」。判据与数值见 {@link CrushingParticleMath}。</p>
 */
@Mixin(CrushingWheelControllerBlockEntity.class)
public abstract class CrushingWheelControllerBeParticleMixin extends SmartBlockEntity {

    private CrushingWheelControllerBeParticleMixin(
            BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @WrapMethod(
            method = "spawnParticles(Lnet/minecraft/world/item/ItemStack;)V",
            require = 1,
            allow = 1)
    private void dimblend$scopeCrushingParticles(ItemStack stack, Operation<Void> original) {
        if (this.level == null
                || !this.level.isClientSide
                || Sable.HELPER.getContainingClient(this.worldPosition) == null) {
            original.call(stack);
            return;
        }
        CrushingWheelParticleScope.enter();
        try {
            if (CrushingParticleMath.needsExactDouble(
                    this.worldPosition.getX(), this.worldPosition.getY(), this.worldPosition.getZ())) {
                this.dimblend$spawnExactParticles(stack);
                return;
            }
            original.call(stack);
        } finally {
            CrushingWheelParticleScope.exit();
        }
    }

    /**
     * {@code CrushingWheelControllerBlockEntity#spawnParticles} 的逐句复刻（Create 6.0.10 字节码
     * {@code 0..145}），只把坐标那一步换成 {@link CrushingParticleMath#exactJitteredCoordinate}。
     *
     * <p>随机数消耗与 Create 一致：每轮三颗 {@code nextFloat()}，共四轮十二次。</p>
     */
    @Unique
    private void dimblend$spawnExactParticles(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        final ParticleOptions particleData;
        if (stack.getItem() instanceof BlockItem blockItem) {
            particleData = new BlockParticleOption(
                    ParticleTypes.BLOCK, blockItem.getBlock().defaultBlockState());
        } else {
            particleData = new ItemParticleOption(ParticleTypes.ITEM, stack);
        }
        final RandomSource random = this.level.random;
        for (int i = 0; i < 4; i++) {
            this.level.addParticle(
                    particleData,
                    CrushingParticleMath.exactJitteredCoordinate(this.worldPosition.getX(), random.nextFloat()),
                    CrushingParticleMath.exactJitteredCoordinate(this.worldPosition.getY(), random.nextFloat()),
                    CrushingParticleMath.exactJitteredCoordinate(this.worldPosition.getZ(), random.nextFloat()),
                    0.0D, 0.0D, 0.0D);
        }
    }
}

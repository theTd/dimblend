package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 标记 Sable 子层级上粉碎轮粉碎物品时的方块/物品碎屑粒子。
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
            original.call(stack);
        } finally {
            CrushingWheelParticleScope.exit();
        }
    }
}

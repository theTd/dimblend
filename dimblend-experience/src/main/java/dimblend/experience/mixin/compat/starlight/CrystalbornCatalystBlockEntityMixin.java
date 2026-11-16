package dimblend.experience.mixin.compat.starlight;

import cn.leolezury.eternalstarlight.common.block.CrystalbornCatalystBlock;
import cn.leolezury.eternalstarlight.common.block.entity.CrystalbornCatalystBlockEntity;
import dimblend.experience.compat.starlight.CrystalbornCatalystRedstoneState;
import dimblend.experience.compat.starlight.CrystalbornCatalystRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects the catalyst tick while a redstone block touches it:
 * <ul>
 *   <li>on a Sable structure the catalyst removes itself (placement is already banned);</li>
 *   <li>with {@code LIT} and an adjacent redstone block, cursors mine the same block type as the
 *       template below the catalyst, never the block directly below or the catalyst itself.</li>
 * </ul>
 * The upstream cursor loop is skipped via {@code cancel()} in that mode.
 */
@Mixin(value = CrystalbornCatalystBlockEntity.class, remap = false)
public abstract class CrystalbornCatalystBlockEntityMixin implements CrystalbornCatalystRedstoneState {

    @Unique
    private final List<CrystalbornCatalystRules.RedstoneCursor> dimblend$redstoneCursors = new ArrayList<>();

    @Override
    public List<CrystalbornCatalystRules.RedstoneCursor> cursors() {
        return this.dimblend$redstoneCursors;
    }

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcn/leolezury/eternalstarlight/common/block/entity/CrystalbornCatalystBlockEntity;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private static void dimblend$tickRedstoneMode(Level level, BlockPos pos, BlockState state,
            CrystalbornCatalystBlockEntity entity, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (CrystalbornCatalystRules.isOnSableStructure(serverLevel, pos)) {
            serverLevel.destroyBlock(pos, true);
            ci.cancel();
            return;
        }
        if (!CrystalbornCatalystRules.hasNeighborRedstoneBlock(serverLevel, pos)
                || !state.getValue(CrystalbornCatalystBlock.LIT)) {
            return;
        }
        CrystalbornCatalystRules.tickRedstoneBlockMode(serverLevel, pos, state, entity,
                (CrystalbornCatalystBlockEntityAccessor) (Object) entity,
                ((CrystalbornCatalystRedstoneState) (Object) entity).cursors());
        ci.cancel();
    }
}

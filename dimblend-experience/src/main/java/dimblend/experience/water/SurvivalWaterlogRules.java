package dimblend.experience.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 生存模式禁止手持水桶右键可含水方块创建含水方块（不建含水方块、不扣水、不做任何提示）。
 */
public final class SurvivalWaterlogRules {

    public static boolean shouldBlockWaterlog(boolean hasInfiniteMaterials, boolean isWaterBucket, boolean isWaterloggableTarget) {
        return !hasInfiniteMaterials && isWaterBucket && isWaterloggableTarget;
    }

    public static boolean shouldAbortBucketUseOnBlock(BucketItem bucket, Player player, Level level, BlockPos hitPos) {
        if (bucket instanceof MobBucketItem || player.isSpectator()) {
            return false;
        }
        boolean isWater = bucket.content.is(FluidTags.WATER);
        if (!isWater || player.hasInfiniteMaterials()) {
            return false;
        }
        if (!chunkReady(level, hitPos)) {
            return false;
        }
        BlockState state = level.getBlockState(hitPos);
        boolean waterloggable = state.hasProperty(BlockStateProperties.WATERLOGGED)
                || (state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(player, level, hitPos, state, bucket.content));
        return shouldBlockWaterlog(player.hasInfiniteMaterials(), true, waterloggable);
    }

    private static boolean chunkReady(Level level, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getChunkSource().getChunkNow(cx, cz) != null;
        }
        return level.hasChunk(cx, cz);
    }

    private SurvivalWaterlogRules() {
    }
}

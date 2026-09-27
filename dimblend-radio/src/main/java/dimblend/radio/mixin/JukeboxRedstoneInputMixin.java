package dimblend.radio.mixin;

import org.spongepowered.asm.mixin.Mixin;

import dimblend.radio.server.RadioSync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 唱片机红石输入：邻居变化即时重算 + 空盘不中继强充能。
 *
 * <p>原版 {@code JukeboxBlock} 不声明 {@code neighborChanged}/{@code shouldCheckWeakPower}，
 * 无法 {@code @Inject}；本 mixin 继承目标父类 {@link BaseEntityBlock}，以新增覆写的方式
 * 并入 {@code JukeboxBlock}（super 调用落到原继承链）。</p>
 *
 * <ul>
 *   <li>{@code neighborChanged}：任一相邻方块变化（贴侧红石块放上/拿走、落地拉杆、
 *       红石线、中继器等）当场重算，不等 20 tick 看门狗。</li>
 *   <li>{@code shouldCheckWeakPower}：空盘（{@code HAS_RECORD=false}）时唱片机不再当
 *       导体中继强充能。贴在唱片机上的拉杆/模拟拉杆会强充能唱片机，原版会把这股电
 *       再喂给其它面上的红石线——顶部模拟拉杆抬高侧面红石线（音量跟着台号走、侧面
 *       拉低也停不了），侧面拉杆把顶部红石线顶成 15（旁路不开播）。空盘不中继后
 *       顶/侧各读各的输入；有盘时恢复原版导体行为。</li>
 * </ul>
 */
@Mixin(JukeboxBlock.class)
public abstract class JukeboxRedstoneInputMixin extends BaseEntityBlock {

    protected JukeboxRedstoneInputMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel serverLevel) {
            RadioSync.onNeighborChanged(serverLevel, pos);
        }
    }

    @Override
    public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) {
        if (!state.getValue(JukeboxBlock.HAS_RECORD)) {
            return false;
        }
        return super.shouldCheckWeakPower(state, level, pos, side);
    }
}

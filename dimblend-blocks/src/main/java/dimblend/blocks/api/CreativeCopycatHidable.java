package dimblend.blocks.api;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造伪装方块 BE 的「隐藏」状态：BE 级持久标记，刻意不走方块状态——
 * Sable 子关卡里每次改 BlockState 都会触发物理碰撞体/质量更新（carwash 既定约束，
 * 见 dimblend-carwash README）。隐藏 = 不渲染（手持扳手时幽灵显现）、碰撞箱保留、
 * 洗车脏值冻结（dimblend-carwash 经 {@link CreativeCopycatHiding#isHidden} 查询）。
 *
 * <p>依赖 Create 的类型只出现在方法体内（JVM 惰性解析）：实现本接口的 BE 仅在
 * Copycats+/Create 在场时才注册，接口本身的加载与 {@code instanceof} 判定无副作用。</p>
 */
public interface CreativeCopycatHidable {

    String HIDDEN_KEY = "CreativeHidden";

    boolean isCopycatHidden();

    /** 仅写字段；存盘/同步/重绘由 {@link #setCopycatHidden} 负责。 */
    void setCopycatHiddenRaw(boolean hidden);

    /**
     * 切换隐藏状态（幂等）。服务端标区块待存盘并 {@code sendData} 同步
     * （与脏值同纪律：不走 {@code setChanged} 的邻居通知）；客户端本地即时重绘，
     * 服务端同步包到达后幂等重放一次。
     */
    default void setCopycatHidden(boolean hidden) {
        if (isCopycatHidden() == hidden) {
            return;
        }
        setCopycatHiddenRaw(hidden);
        BlockEntity blockEntity = (BlockEntity) this;
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }
        if (level.isClientSide()) {
            redrawAfterHiddenChange(blockEntity);
        } else {
            level.blockEntityChanged(blockEntity.getBlockPos());
            if (blockEntity instanceof SyncedBlockEntity synced) {
                synced.sendData();
            }
        }
    }

    /** NBT 读取钩子（接在 BE 的 {@code read} 尾部）：客户端收到同步包且值变化时重绘。 */
    default void readHidden(CompoundTag nbt, boolean clientPacket) {
        boolean previous = isCopycatHidden();
        setCopycatHiddenRaw(nbt.getBoolean(HIDDEN_KEY));
        if (clientPacket && previous != isCopycatHidden()) {
            redrawAfterHiddenChange((BlockEntity) this);
        }
    }

    /** NBT 写入钩子（接在 BE 的 {@code write} 尾部）：缺省 false 不落键。 */
    default void writeHidden(CompoundTag nbt) {
        if (isCopycatHidden()) {
            nbt.putBoolean(HIDDEN_KEY, true);
        }
    }

    /**
     * 同 Create 伪装方块换材质后的重绘（也即 carwash {@code ChassisGrimeBehaviour.redraw}
     * 的既定纪律）：刷新模型数据并标脏所在区段，客户端重建网格时经模型包装层读到新状态。
     */
    static void redrawAfterHiddenChange(BlockEntity blockEntity) {
        if (!(blockEntity instanceof SmartBlockEntity smart) || !smart.isVirtual()) {
            blockEntity.requestModelDataUpdate();
        }
        Level level = blockEntity.getLevel();
        if (level != null) {
            BlockPos pos = blockEntity.getBlockPos();
            BlockState state = blockEntity.getBlockState();
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_KNOWN_SHAPE);
        }
    }
}

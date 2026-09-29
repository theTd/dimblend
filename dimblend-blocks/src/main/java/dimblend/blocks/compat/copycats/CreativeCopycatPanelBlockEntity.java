package dimblend.blocks.compat.copycats;

import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import dimblend.blocks.api.CreativeCopycatHidable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式伪装板（含洁净变体）的 BE：在 Create {@link CopycatBlockEntity} 上叠加
 * 「隐藏」持久标记，读写与重绘纪律见 {@link CreativeCopycatHidable}。
 */
public class CreativeCopycatPanelBlockEntity extends CopycatBlockEntity implements CreativeCopycatHidable {

    /** 网格构建线程（HidingCopycatModel）与主线程都会读，故 volatile。 */
    private volatile boolean hidden;

    public CreativeCopycatPanelBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean isCopycatHidden() {
        return hidden;
    }

    @Override
    public void setCopycatHiddenRaw(boolean hidden) {
        this.hidden = hidden;
    }

    @Override
    protected void write(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(nbt, registries, clientPacket);
        writeHidden(nbt);
    }

    @Override
    protected void read(CompoundTag nbt, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(nbt, registries, clientPacket);
        readHidden(nbt, clientPacket);
    }
}

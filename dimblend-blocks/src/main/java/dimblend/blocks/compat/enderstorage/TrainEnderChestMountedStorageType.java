package dimblend.blocks.compat.enderstorage;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 列车末影物流箱的 Create 挂载存储类型定义。
 */
public class TrainEnderChestMountedStorageType extends MountedItemStorageType<TrainEnderChestMountedStorage> {

    public TrainEnderChestMountedStorageType() {
        super(TrainEnderChestMountedStorage.CODEC);
    }

    @Override
    @Nullable
    public TrainEnderChestMountedStorage mount(
            Level level, BlockState blockState, BlockPos blockPos, @Nullable BlockEntity blockEntity) {
        if (blockEntity instanceof TrainEnderChestBlockEntity enderChest) {
            return new TrainEnderChestMountedStorage(enderChest.getFrequency(), enderChest.rotation);
        }
        return null;
    }
}

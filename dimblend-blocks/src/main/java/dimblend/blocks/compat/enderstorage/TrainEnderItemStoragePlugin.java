package dimblend.blocks.compat.enderstorage;

import java.util.List;

import codechicken.enderstorage.api.EnderStoragePlugin;
import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.api.StorageType;
import codechicken.enderstorage.manager.EnderStorageManager;
import net.minecraft.server.level.ServerPlayer;

/**
 * 将 {@link TrainEnderItemStorage} 注册进 {@link EnderStorageManager} 的插件定义。
 */
public class TrainEnderItemStoragePlugin implements EnderStoragePlugin<TrainEnderItemStorage> {

    @Override
    public TrainEnderItemStorage createEnderStorage(EnderStorageManager manager, Frequency freq) {
        return new TrainEnderItemStorage(manager, freq);
    }

    @Override
    public StorageType<TrainEnderItemStorage> identifier() {
        return TrainEnderItemStorage.TYPE;
    }

    @Override
    public void sendClientInfo(ServerPlayer player, List<TrainEnderItemStorage> list) {
        // 列车末影物流箱没有手持末影袋形态，方块开盖人数由区块内 blockEvent(1, c_numOpen) 同步。
    }
}

package dimblend.blocks.compat.enderstorage;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.api.StorageType;
import codechicken.enderstorage.manager.EnderStorageManager;
import codechicken.enderstorage.storage.EnderItemStorage;

/**
 * 列车末影物流箱专用存储通道（{@code type=dimblend_train_item}）。
 *
 * <p>继承 {@link EnderItemStorage} 复用全部容量档位、容器菜单与双备份落盘（{@code EnderStorage/data1.dat}
 * 与 {@code data2.dat}），但使用独立的 {@link #TYPE} 键，不与地面 {@code enderstorage:ender_chest}
 * 或手持 {@code enderstorage:ender_pouch}（{@code type=item}）互通。</p>
 *
 * <p>重写 {@link #openInventory()} / {@link #closeInventory()} 在服务端独立维护打开计数，
 * 供 {@link TrainEnderChestBlockEntity} 经原版 {@code level.blockEvent(pos, block, 1, c_numOpen)}
 * 同步开盖动画与音效，而不向 {@code EnderItemStorage.TYPE} 发送末影袋开合包（packet 2）。</p>
 */
public class TrainEnderItemStorage extends EnderItemStorage {

    public static final String TYPE_NAME = "dimblend_train_item";
    public static final StorageType<TrainEnderItemStorage> TYPE = new StorageType<>(TYPE_NAME);

    private int trainOpen;

    public TrainEnderItemStorage(EnderStorageManager manager, Frequency freq) {
        super(manager, freq);
    }

    @Override
    public String type() {
        return TYPE_NAME;
    }

    @Override
    public void openInventory() {
        if (this.manager.client) {
            return;
        }
        synchronized (this) {
            ++this.trainOpen;
        }
    }

    @Override
    public void closeInventory() {
        if (this.manager.client) {
            return;
        }
        synchronized (this) {
            if (this.trainOpen > 0) {
                --this.trainOpen;
            }
        }
    }

    @Override
    public int getNumOpen() {
        return this.trainOpen;
    }

    @Override
    public int openCount() {
        return this.trainOpen;
    }
}

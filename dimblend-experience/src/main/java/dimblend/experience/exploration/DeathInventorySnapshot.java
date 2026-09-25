package dimblend.experience.exploration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 死亡瞬间的整栏位快照（主背包 36 + 盔甲 4 + 副手 1，按槽位原序），用于 A1
 * “死亡保留物品”。槽位原序还原让玩家的快捷栏排布在重生后保持不变。
 */
public record DeathInventorySnapshot(List<ItemStack> stacks, int selected) {

    public static final Codec<DeathInventorySnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("stacks").forGetter(DeathInventorySnapshot::stacks),
            Codec.INT.fieldOf("selected").forGetter(DeathInventorySnapshot::selected)).apply(instance, DeathInventorySnapshot::new));

    public static final DeathInventorySnapshot EMPTY = new DeathInventorySnapshot(List.of(), -1);

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    public static DeathInventorySnapshot capture(Player player) {
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            stacks.add(inventory.getItem(i).copy());
        }
        return new DeathInventorySnapshot(List.copyOf(stacks), inventory.selected);
    }

    public void restore(Player player) {
        Inventory inventory = player.getInventory();
        int slots = Math.min(stacks.size(), inventory.getContainerSize());
        for (int i = 0; i < slots; i++) {
            inventory.setItem(i, stacks.get(i));
        }
        if (selected >= 0 && selected < Inventory.getSelectionSize()) {
            inventory.selected = selected;
        }
    }
}

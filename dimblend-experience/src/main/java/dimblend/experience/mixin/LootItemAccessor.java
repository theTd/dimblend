package dimblend.experience.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取条目物品，用于把过滤限定在 {@code minecraft:map}
 * 条目上（空地图等其它条目不动）。
 */
@Mixin(LootItem.class)
public interface LootItemAccessor {

    @Accessor("item")
    Holder<Item> dimblend$getItem();
}

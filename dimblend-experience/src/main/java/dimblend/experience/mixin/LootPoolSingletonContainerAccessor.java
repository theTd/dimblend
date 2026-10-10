package dimblend.experience.mixin;

import java.util.List;

import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取单条目（LootItem 等）挂的函数列表，
 * 用于发现 exploration_map 函数。
 */
@Mixin(LootPoolSingletonContainer.class)
public interface LootPoolSingletonContainerAccessor {

    @Accessor("functions")
    List<LootItemFunction> dimblend$getFunctions();
}

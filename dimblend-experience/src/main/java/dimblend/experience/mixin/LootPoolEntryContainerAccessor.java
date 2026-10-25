package dimblend.experience.mixin;

import java.util.List;

import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取条目自身的条件列表。把地图条目重建为纸条目时
 * 原样保留 conditions（权重/品质由 {@link LootPoolSingletonContainerAccessor} 读取），
 * 只丢弃 functions 使产出恒为 1 张纸。
 */
@Mixin(LootPoolEntryContainer.class)
public interface LootPoolEntryContainerAccessor {

    @Accessor("conditions")
    List<LootItemCondition> dimblend$getConditions();
}

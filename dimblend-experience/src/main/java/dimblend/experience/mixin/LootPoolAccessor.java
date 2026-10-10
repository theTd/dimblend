package dimblend.experience.mixin;

import java.util.List;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读写 pool 条目列表。codec 解码产物是 ImmutableList，
 * 过滤经 {@link #dimblend$setEntries} 整体换引用实现（不能原地 removeIf）。
 * {@code LootPool} 未缓存 entries 的派生量（每轮 roll 直接迭代本列表），换引用安全。
 */
@Mixin(LootPool.class)
public interface LootPoolAccessor {

    @Accessor("entries")
    List<LootPoolEntryContainer> dimblend$getEntries();

    @Mutable
    @Accessor("entries")
    void dimblend$setEntries(List<LootPoolEntryContainer> entries);
}

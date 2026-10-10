package dimblend.experience.mixin;

import java.util.List;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取战利品表的 pool 列表（NeoForge 只暴露按名取 pool 的
 * {@code getPool}，遍历全表需经此访问器）。
 */
@Mixin(LootTable.class)
public interface LootTableAccessor {

    @Accessor("pools")
    List<LootPool> dimblend$getPools();
}

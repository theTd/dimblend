package dimblend.experience.mixin;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取 exploration_map 函数的 destination 结构 tag
 * （原版藏宝图与 Dungeons Arise 探险地图的判定依据）。
 */
@Mixin(ExplorationMapFunction.class)
public interface ExplorationMapFunctionAccessor {

    @Accessor("destination")
    TagKey<Structure> dimblend$getDestination();
}

package dimblend.experience.mixin;

import java.util.List;

import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A10 战利品地图过滤：读取 alternatives / group / sequence 的子条目列表，
 * 递归改写嵌套地图条目。复合材料在构造时就把 children 固化成
 * {@code composedChildren}，因此不能原地改 children，必须用公开 Builder 整体重建。
 */
@Mixin(CompositeEntryBase.class)
public interface CompositeEntryBaseAccessor {

    @Accessor("children")
    List<LootPoolEntryContainer> dimblend$getChildren();
}

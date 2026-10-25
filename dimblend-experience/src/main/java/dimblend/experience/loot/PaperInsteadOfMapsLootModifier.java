package dimblend.experience.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import dimblend.experience.Config;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/**
 * A10 战利品地图过滤的第三层（最终网）：无条件的 NeoForge 全局战利品修饰器。
 *
 * <p>战利品表生成完、所有 GLM 依次跑完后，把<b>每一个</b>最终地图堆
 * （{@code Items.MAP}、{@code Items.FILLED_MAP} 或任何 {@link net.minecraft.world.item.MapItem} 子类）
 * 换成 {@code ItemStack(Items.PAPER, 1)}。它不看 exploration_map 函数、不看
 * destination、不依赖表加载替换是否命中——模组直接往 loot list 塞成品地图、
 * 自定义 GLM、程序化战利品表等前两层覆盖不到的路径都能兜住。条件为空，
 * 对全部战利品表生效；{@code lootMapFilter} 关闭时原样返回。</p>
 *
 * <p>JSON：{@code data/dimblend_experience/loot_modifiers/paper_instead_of_maps.json}
 * （{@code "type": "dimblend_experience:paper_instead_of_maps", "conditions": []}），
 * 并在 {@code data/neoforge/loot_modifiers/global_loot_modifiers.json} 的 entries
 * 中登记。序列化器经 {@link LootMapGlobalModifiers} 在
 * {@code NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS} 注册。</p>
 */
public class PaperInsteadOfMapsLootModifier extends LootModifier {

    public static final MapCodec<PaperInsteadOfMapsLootModifier> CODEC = RecordCodecBuilder.mapCodec(
            instance -> codecStart(instance).apply(instance, PaperInsteadOfMapsLootModifier::new));

    public PaperInsteadOfMapsLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (Config.isLoaded() && !Config.LOOT_MAP_FILTER.get()) {
            return generatedLoot;
        }
        for (int i = 0; i < generatedLoot.size(); i++) {
            if (MapLootEntryRewrite.isMapItem(generatedLoot.get(i).getItem())) {
                generatedLoot.set(i, new ItemStack(Items.PAPER));
            }
        }
        return generatedLoot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}

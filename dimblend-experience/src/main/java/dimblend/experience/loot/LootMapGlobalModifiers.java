package dimblend.experience.loot;

import com.mojang.serialization.MapCodec;

import dimblend.experience.DimBlend;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * A10 第三层全局战利品修饰器的序列化器注册。与 {@code ModSounds} /
 * {@code ExplorationAttachments} 同型：{@link DeferredRegister} 在 mod 总线注册，
 * 注册表键取 {@code NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS}
 * （21.1.249 已核对：该注册表类型为 {@code Registry<MapCodec<? extends IGlobalLootModifier>>}）。
 * 无条件注册——不依赖任何第三方模组。
 */
public final class LootMapGlobalModifiers {

    public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, DimBlend.MODID);

    /** JSON 类型 id {@code dimblend_experience:paper_instead_of_maps}。 */
    public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>,
            MapCodec<PaperInsteadOfMapsLootModifier>> PAPER_INSTEAD_OF_MAPS =
            SERIALIZERS.register("paper_instead_of_maps", () -> PaperInsteadOfMapsLootModifier.CODEC);

    private LootMapGlobalModifiers() {
    }
}

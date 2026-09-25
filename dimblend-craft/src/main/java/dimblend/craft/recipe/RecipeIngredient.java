package dimblend.craft.recipe;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 规则表中的材料描述：物品 ID 或物品标签 ID。
 *
 * <p>规则在数据包重载阶段才解析成 {@link Ingredient}：此时所有 mod 的物品注册已冻结，
 * 而 mod 构造期目标 mod 的物品尚未注册，因此规则表只保存 ID 字符串。</p>
 */
public record RecipeIngredient(ResourceLocation id, boolean tag) {

    public static RecipeIngredient item(String id) {
        return new RecipeIngredient(ResourceLocation.parse(id), false);
    }

    public static RecipeIngredient tag(String id) {
        return new RecipeIngredient(ResourceLocation.parse(id), true);
    }

    /**
     * 解析为 Ingredient。
     *
     * @return 物品未注册时返回 empty，由调用方决定是否跳过整条规则；
     *         标签不做存在性校验（标签由数据包在匹配期解析，本 mod 随包提供 c:shulker_boxes 定义）。
     */
    public Optional<Ingredient> resolve() {
        if (tag) {
            return Optional.of(Ingredient.of(TagKey.create(Registries.ITEM, id)));
        }
        return BuiltInRegistries.ITEM.getOptional(id).map(Ingredient::of);
    }

    /** 日志用描述。 */
    public String describe() {
        return (tag ? "tag " : "") + id;
    }
}
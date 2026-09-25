package dimblend.experience.datagen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
/**
 * 为 factory_blocks 的 42 种纯装饰方块生成切石机配方：铁块 x1 -&gt; 装饰块 x4。
 *
 * <p>刻意写原始 JSON 而不走 {@code SingleItemRecipeBuilder}：factory_blocks 是 optional 依赖，
 * datagen 运行时该 mod 可能不在场，无法拿到其 {@code Item} Holder；字符串 ID + 手写 JSON 不依赖其上 classpath。
 *
 * <p>方块 ID 表见 {@link FactoryStonecuttingBlocks}（纯 Java，单测可直接引用）。
 */
public class FactoryStonecuttingProvider implements DataProvider {
    public static final String FACTORY_MODID = "factory_blocks";
    public static final String GROUP = "dimblend_factory";

    private final PackOutput output;

    public FactoryStonecuttingProvider(PackOutput output) {
        this.output = output;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cached) {
        Path root = this.output.getOutputFolder(PackOutput.Target.DATA_PACK);
        List<String> blocks = FactoryStonecuttingBlocks.DECORATIVE_BLOCKS;
        List<CompletableFuture<?>> futures = new ArrayList<>(blocks.size());
        for (String name : blocks) {
            // getOutputFolder(DATA_PACK) 已指向 data/ 根目录，此处不再重复拼接 data/
            Path path = root.resolve("dimblend_experience/recipe/stonecutting/" + name + "_from_iron_block.json");
            futures.add(DataProvider.saveStable(cached, recipeJson(name), path));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private static JsonObject recipeJson(String name) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", "minecraft:iron_block");

        JsonObject result = new JsonObject();
        result.addProperty("id", FACTORY_MODID + ":" + name);
        result.addProperty("count", 4);

        JsonObject condition = new JsonObject();
        condition.addProperty("type", "neoforge:mod_loaded");
        condition.addProperty("modid", FACTORY_MODID);
        JsonArray conditions = new JsonArray();
        conditions.add(condition);

        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:stonecutting");
        recipe.addProperty("group", GROUP);
        recipe.add("ingredient", ingredient);
        recipe.add("result", result);
        recipe.add("neoforge:conditions", conditions);
        return recipe;
    }

    @Override
    public String getName() {
        return "dimblend_experience factory stonecutting";
    }
}

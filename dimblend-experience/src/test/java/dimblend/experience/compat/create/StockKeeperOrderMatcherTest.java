package dimblend.experience.compat.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.create.StockKeeperOrderMatcher.Candidate;
import dimblend.experience.compat.create.StockKeeperOrderMatcher.Selection;
import dimblend.experience.compat.create.StockKeeperOrderMatcher.StackView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 仓库管理员订单宽松匹配的纯逻辑单测。
 *
 * <p>不构造 {@link net.minecraft.world.item.ItemStack}：本模块测试运行时没有 Minecraft
 * 注册表，{@code ItemStack.<clinit>} 会因 "Not bootstrapped" 失败（同类先例见
 * {@code VillagerTradeSwapTest}）。生产适配层（{@code viewOf}）只能实机验证。</p>
 */
class StockKeeperOrderMatcherTest {

    private static final ResourceLocation DAMAGE = id("damage");
    private static final ResourceLocation CUSTOM_NAME = id("custom_name");
    private static final ResourceLocation CUSTOM_DATA = id("custom_data");

    @Test
    void identicalStacksAreCompatible() {
        StackView left = view("minecraft:iron_sword", 1, Map.of(DAMAGE, 0));
        StackView right = view("minecraft:iron_sword", 1, Map.of(DAMAGE, 0));

        assertTrue(StockKeeperOrderMatcher.compatible(left, right));
    }

    @Test
    void stackableItemsRejectAnyVisibleComponentDifference() {
        StackView ordered = view("minecraft:stone", 64, Map.of(DAMAGE, 0));
        StackView candidate = view("minecraft:stone", 64, Map.of(DAMAGE, 1));

        assertFalse(StockKeeperOrderMatcher.compatible(ordered, candidate));
    }

    @Test
    void stackableItemsMatchWhenVisibleComponentsAgree() {
        StackView ordered = view("minecraft:stone", 64, Map.of(CUSTOM_NAME, "Stone"));
        StackView candidate = view("minecraft:stone", 64, Map.of(CUSTOM_NAME, "Stone"));

        assertTrue(StockKeeperOrderMatcher.compatible(ordered, candidate));
    }

    @Test
    void unstackableItemsIgnoreComponentsOutsideTheIdentitySet() {
        StackView ordered = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0));
        // 点击后 tooltip/渲染路径懒挂载的组件：不改变物品身份，必须容忍。
        StackView lazilyAttached = view("minecraft:diamond_chestplate", 1,
                Map.of(DAMAGE, 0, CUSTOM_DATA, "{lazy:1}"));

        assertTrue(StockKeeperOrderMatcher.compatible(ordered, lazilyAttached));
    }

    @Test
    void unstackableItemsStillRequireIdentityComponents() {
        StackView ordered = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0, CUSTOM_NAME, "Held"));
        StackView renamed = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0, CUSTOM_NAME, "Other"));

        assertFalse(StockKeeperOrderMatcher.compatible(ordered, renamed));
    }

    @Test
    void differentItemsAreNeverCompatible() {
        StackView ordered = view("minecraft:iron_sword", 1, Map.of(DAMAGE, 0));
        StackView candidate = view("minecraft:golden_sword", 1, Map.of(DAMAGE, 0));

        assertFalse(StockKeeperOrderMatcher.compatible(ordered, candidate));
    }

    @Test
    void selectionCarriesPayloadAndAvailableCount() {
        StackView ordered = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0));
        List<Candidate<String>> candidates = List.of(
                new Candidate<>("snapshot-entry", ordered, 7));

        Optional<Selection<String>> selection = StockKeeperOrderMatcher.selectUnique(ordered, candidates);

        assertTrue(selection.isPresent());
        assertEquals("snapshot-entry", selection.get().payload());
        assertEquals(7, selection.get().available());
    }

    @Test
    void ambiguousMatchesYieldNothing() {
        StackView ordered = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0));
        List<Candidate<String>> candidates = List.of(
                new Candidate<>("first", ordered, 1),
                new Candidate<>("second", ordered, 1));

        assertTrue(StockKeeperOrderMatcher.selectUnique(ordered, candidates).isEmpty());
    }

    @Test
    void nonPositiveCountsAreIgnored() {
        StackView ordered = view("minecraft:diamond_chestplate", 1, Map.of(DAMAGE, 0));
        List<Candidate<String>> candidates = List.of(
                new Candidate<>("empty", ordered, 0),
                new Candidate<>("negative", ordered, -5),
                new Candidate<>("real", ordered, 3));

        Optional<Selection<String>> selection = StockKeeperOrderMatcher.selectUnique(ordered, candidates);

        assertTrue(selection.isPresent());
        assertEquals("real", selection.get().payload());
        assertEquals(3, selection.get().available());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("minecraft", path);
    }

    private static StackView view(Object itemKey, int maxStackSize, Map<ResourceLocation, Object> components) {
        return new StackView() {

            @Override
            public Object itemKey() {
                return itemKey;
            }

            @Override
            public int maxStackSize() {
                return maxStackSize;
            }

            @Override
            public Map<ResourceLocation, Object> visibleComponents() {
                return components;
            }
        };
    }
}

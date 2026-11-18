package dimblend.experience.compat.toolbelt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ToolBeltBackpackGuard} 的纯判据单测：注册名与类名两条识别路径都必须覆盖
 * Sophisticated Backpacks 本体与附加模组的背包，且不能误伤普通物品。
 */
class ToolBeltBackpackGuardTest {

    @Test
    void sophisticatedBackpackRegistryNamesAreRejected() {
        assertTrue(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", "backpack"));
        assertTrue(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", "iron_backpack"));
        assertTrue(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", "netherite_backpack"));
    }

    @Test
    void upgradesAndForeignNamespacesPassThrough() {
        assertFalse(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", "pickup_upgrade"));
        assertFalse(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", "backpack_upgrade"));
        assertFalse(ToolBeltBackpackGuard.isBackpackRegistryName("minecraft", "shulker_box"));
        assertFalse(ToolBeltBackpackGuard.isBackpackRegistryName(null, "backpack"));
        assertFalse(ToolBeltBackpackGuard.isBackpackRegistryName("sophisticatedbackpacks", null));
    }

    @Test
    void classChainMarkerCatchesSubclassesFromOtherAddons() {
        assertTrue(ToolBeltBackpackGuard.isBackpackClassChain(FakeBackpackItem.class));
        assertTrue(ToolBeltBackpackGuard.isBackpackClassChain(SpecialBackpackItem.class));
        assertFalse(ToolBeltBackpackGuard.isBackpackClassChain(String.class));
        assertFalse(ToolBeltBackpackGuard.isBackpackClassChain(null));
    }

    /** 模拟 Sophisticated Backpacks 的物品基类。 */
    private static class FakeBackpackItem {
    }

    /** 模拟附加模组继承背包基类注册的背包物品。 */
    private static final class SpecialBackpackItem extends FakeBackpackItem {
    }
}

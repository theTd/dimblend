package dimblend.experience.compat.enderstorage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5 受限方块识别（纯函数，不启动游戏、不碰注册表）。
 */
class EnderStorageBlockIdsTest {

    @Test
    void matchesEnderChestAndTank() {
        assertTrue(EnderStorageBlockIds.isGuarded("enderstorage", "ender_chest"));
        assertTrue(EnderStorageBlockIds.isGuarded("enderstorage", "ender_tank"));
    }

    @Test
    void rejectsVanillaEnderChestAndOtherIds() {
        // 原版末影箱不在需求范围内
        assertFalse(EnderStorageBlockIds.isGuarded("minecraft", "ender_chest"));
        // 末影袋是物品不是方块；其他命名空间同名路径也不中
        assertFalse(EnderStorageBlockIds.isGuarded("enderstorage", "ender_pouch"));
        assertFalse(EnderStorageBlockIds.isGuarded("createenderstorage", "ender_tank"));
    }
}

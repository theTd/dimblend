package dimblend.experience.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A10 探索地图 destination 禁令（纯函数，不启动游戏、不碰注册表）。
 */
class ExplorationMapDestinationBanTest {

    @Test
    void bansVanillaTreasureMapTag() {
        assertTrue(ExplorationMapLootFilter.isBannedDestinationId("minecraft", "on_treasure_maps"));
    }

    @Test
    void bansDungeonsAriseNamespaces() {
        assertTrue(ExplorationMapLootFilter.isBannedDestinationId("dungeons_arise", "explorer_maps/monastery"));
        assertTrue(ExplorationMapLootFilter.isBannedDestinationId("dungeons_arise", "bandit_towers"));
        assertTrue(ExplorationMapLootFilter.isBannedDestinationId("dungeons_arise_seven_seas", "corsair_corvette"));
    }

    @Test
    void allowsOtherExplorerMapTags() {
        // 林地府邸/海底神殿等其它探索地图 tag 不在禁令内
        assertFalse(ExplorationMapLootFilter.isBannedDestinationId("minecraft", "on_woodland_explorer_maps"));
        assertFalse(ExplorationMapLootFilter.isBannedDestinationId("minecraft", "on_ocean_explorer_maps"));
        // 藏宝图 tag 的路径必须精确命中，命名空间不同不中
        assertFalse(ExplorationMapLootFilter.isBannedDestinationId("twilightforest", "on_treasure_maps"));
        assertFalse(ExplorationMapLootFilter.isBannedDestinationId("minecraft", "village"));
    }
}

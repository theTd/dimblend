package dimblend.dragonchase.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the nest's reward lists to {@code docs/structures.md}: 20 storage blocks with the requirement's
 * exact mix, 27 filled shulker slots, and a shuffle that is a function of the seed and never puts two
 * rewards in one cell or one slot.
 */
class NestRewardTableTest {

    @Test
    void storageListIsTwentyBlocksWithTheRequiredMix() {
        List<String> ids = NestRewardTable.storageIds();
        assertEquals(20, ids.size());
        Map<String, Integer> counts = countById(ids);
        assertEquals(2, counts.get("minecraft:copper_block"));
        assertEquals(8, counts.get("minecraft:iron_block"));
        assertEquals(5, counts.get("create:zinc_block"));
        assertEquals(2, counts.get("minecraft:gold_block"));
        assertEquals(2, counts.get("minecraft:diamond_block"));
        assertEquals(1, counts.get("minecraft:netherite_block"));
        assertEquals(6, counts.size(), "no block outside the requirement's list");
    }

    @Test
    void shulkerListFillsAllTwentySevenSlotsWithTheRequiredMix() {
        List<NestRewardTable.SlotReward> slots = NestRewardTable.shulkerSlots();
        assertEquals(NestRewardTable.SHULKER_SLOTS, slots.size());
        assertEquals(27, slots.size());

        Map<String, Integer> slotsPerItem = new HashMap<>();
        Map<String, Integer> stackOf = new HashMap<>();
        for (NestRewardTable.SlotReward slot : slots) {
            slotsPerItem.merge(slot.itemId(), 1, Integer::sum);
            stackOf.put(slot.itemId(), slot.count());
        }
        assertEquals(8, slotsPerItem.get("minecraft:experience_bottle"));
        assertEquals(2, slotsPerItem.get("minecraft:dragon_breath"));
        assertEquals(2, slotsPerItem.get("kaleidoscope_end:raw_ender_dragon_meat"));
        assertEquals(1, slotsPerItem.get("kaleidoscope_end:dragon_tooth"));
        assertEquals(2, slotsPerItem.get("kaleidoscope_end:dragon_dust"));
        assertEquals(2, slotsPerItem.get("kaleidoscope_dim_wine:dragon_blood_bottle"));
        assertEquals(2, slotsPerItem.get("kaleidoscope_end:dragon_breath_bucket"));
        assertEquals(4, slotsPerItem.get("kaleidoscope_grilling:dragon_egg_powder"));
        assertEquals(1, slotsPerItem.get("kaleidoscope_end:dragon_egg_liquid"));
        assertEquals(2, slotsPerItem.get("minecraft:totem_of_undying"));
        assertEquals(1, slotsPerItem.get("voidscape:voidic_crystal"));

        assertEquals(16, stackOf.get("minecraft:experience_bottle"));
        assertEquals(16, stackOf.get("minecraft:dragon_breath"));
        assertEquals(4, stackOf.get("kaleidoscope_end:raw_ender_dragon_meat"));
        assertEquals(2, stackOf.get("kaleidoscope_end:dragon_tooth"));
        assertEquals(4, stackOf.get("kaleidoscope_end:dragon_dust"));
        assertEquals(16, stackOf.get("kaleidoscope_dim_wine:dragon_blood_bottle"));
        assertEquals(1, stackOf.get("kaleidoscope_end:dragon_breath_bucket"));
        assertEquals(4, stackOf.get("kaleidoscope_grilling:dragon_egg_powder"));
        assertEquals(8, stackOf.get("kaleidoscope_end:dragon_egg_liquid"));
        assertEquals(1, stackOf.get("minecraft:totem_of_undying"));
        assertEquals(16, stackOf.get("voidscape:voidic_crystal"));
    }

    @Test
    void oreRewardsFillEveryOreCellExactlyOnce() {
        List<NestRewardTable.OreReward> rewards = NestRewardTable.oreRewards(1234L);
        assertEquals(20, rewards.size());
        Set<List<Integer>> cells = new HashSet<>();
        for (NestRewardTable.OreReward reward : rewards) {
            assertTrue(cells.add(List.of(reward.cell().dx(), reward.cell().dy(), reward.cell().dz())),
                    "one block per ore cell");
        }
        assertEquals(20, cells.size());
        assertEquals(countById(NestRewardTable.storageIds()), countById(rewards.stream()
                .map(NestRewardTable.OreReward::blockId).toList()));
    }

    @Test
    void theShuffleIsSeededAndNotTheDrawingOrder() {
        assertEquals(NestRewardTable.oreRewards(7L), NestRewardTable.oreRewards(7L));
        assertEquals(NestRewardTable.slotRewards(7L), NestRewardTable.slotRewards(7L));
        assertNotEquals(NestRewardTable.oreRewards(7L), NestRewardTable.oreRewards(8L));
        assertNotEquals(NestRewardTable.slotRewards(7L), NestRewardTable.slotRewards(8L));
        assertNotEquals(NestRewardTable.storageIds(), NestRewardTable.oreRewards(7L).stream()
                .map(NestRewardTable.OreReward::blockId).toList(), "the blocks are shuffled, not stacked");
    }

    @Test
    void slotRewardsUseEverySlotOnce() {
        List<NestRewardTable.SlotReward> rewards = NestRewardTable.slotRewards(99L);
        Set<Integer> slots = new HashSet<>();
        for (NestRewardTable.SlotReward reward : rewards) {
            assertTrue(slots.add(reward.slot()), "one entry per slot");
        }
        assertEquals(27, slots.size());
        for (int slot = 0; slot < 27; slot++) {
            assertTrue(slots.contains(slot), "slot " + slot + " is used");
        }
    }

    private static Map<String, Integer> countById(List<String> ids) {
        Map<String, Integer> counts = new HashMap<>();
        for (String id : ids) {
            counts.merge(id, 1, Integer::sum);
        }
        return counts;
    }
}

package dimblend.dragonchase.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The nest's two reward tables as pure data, and the seeded shuffles that spread them over the nest.
 *
 * <p><b>Kept free of Minecraft types on purpose.</b> These counts are the requirement's, not a
 * level's, and the unit tests must be able to read them without a game running (the same reason
 * {@link CarPlans} holds no registry access). {@link NestReward} is the half that writes blocks.
 *
 * <p>Tables: requirements.md §3.6 / structures.md 龙巢奖励 — twenty storage blocks over the twenty
 * {@code 矿} cells, and a shulker box whose 27 slots are all filled.
 */
public final class NestRewardTable {

    /** One storage block and the cell it lands on. */
    public record OreReward(CarPlans.Cell cell, String blockId) {
    }

    /** One filled shulker slot. */
    public record SlotReward(int slot, String itemId, int count) {
    }

    /** An id, the stack size it is placed with, and how many cells it fills. */
    private record Entry(String id, int count, int cells) {
        Entry(String id, int cells) {
            this(id, 1, cells);
        }
    }

    /** The twenty storage blocks of the requirement, in its order. */
    private static final List<Entry> STORAGE = List.of(
            new Entry("minecraft:copper_block", 2),
            new Entry("minecraft:iron_block", 8),
            new Entry("create:zinc_block", 5),
            new Entry("minecraft:gold_block", 2),
            new Entry("minecraft:diamond_block", 2),
            new Entry("minecraft:netherite_block", 1));

    /** The twenty-seven shulker slots of the requirement, in its order. */
    private static final List<Entry> SHULKER = List.of(
            new Entry("minecraft:experience_bottle", 16, 8),
            new Entry("minecraft:dragon_breath", 16, 2),
            new Entry("kaleidoscope_end:raw_ender_dragon_meat", 4, 2),
            new Entry("kaleidoscope_end:dragon_tooth", 2, 1),
            new Entry("kaleidoscope_end:dragon_dust", 4, 2),
            new Entry("kaleidoscope_dim_wine:dragon_blood_bottle", 16, 2),
            new Entry("kaleidoscope_end:dragon_breath_bucket", 1, 2),
            new Entry("kaleidoscope_grilling:dragon_egg_powder", 4, 4),
            new Entry("kaleidoscope_end:dragon_egg_liquid", 8, 1),
            new Entry("minecraft:totem_of_undying", 1, 2),
            new Entry("voidscape:voidic_crystal", 16, 1));

    /** How many slots the shulker box has; the requirement fills all of them. */
    public static final int SHULKER_SLOTS = 27;

    private NestRewardTable() {
    }

    /** The storage block ids, one per cell, in the requirement's order. */
    public static List<String> storageIds() {
        List<String> out = new ArrayList<>(32);
        for (Entry entry : STORAGE) {
            for (int i = 0; i < entry.cells(); i++) {
                out.add(entry.id());
            }
        }
        return out;
    }

    /** The shulker entries in the requirement's order, with no slot assigned yet. */
    public static List<SlotReward> shulkerSlots() {
        List<SlotReward> out = new ArrayList<>(SHULKER_SLOTS);
        for (Entry entry : SHULKER) {
            for (int i = 0; i < entry.cells(); i++) {
                out.add(new SlotReward(-1, entry.id(), entry.count()));
            }
        }
        return out;
    }

    /**
     * Shuffles the storage blocks over the twenty ore cells.
     *
     * <p>Both the cells and the block order come from the same {@link Random}, so the result is a
     * function of the seed alone while no cell can receive two blocks.
     */
    public static List<OreReward> oreRewards(long seed) {
        List<CarPlans.Cell> cells = new ArrayList<>(CarPlans.of(CarKind.NEST).oreCells());
        List<String> blocks = storageIds();
        Random random = new Random(seed);
        Collections.shuffle(cells, random);
        Collections.shuffle(blocks, random);
        List<OreReward> out = new ArrayList<>(Math.min(cells.size(), blocks.size()));
        for (int i = 0; i < cells.size() && i < blocks.size(); i++) {
            out.add(new OreReward(cells.get(i), blocks.get(i)));
        }
        return out;
    }

    /** Shuffles the 27 entries over the shulker's 27 slots. */
    public static List<SlotReward> slotRewards(long seed) {
        List<SlotReward> entries = shulkerSlots();
        Collections.shuffle(entries, new Random(seed));
        List<SlotReward> out = new ArrayList<>(entries.size());
        for (int slot = 0; slot < entries.size(); slot++) {
            SlotReward entry = entries.get(slot);
            out.add(new SlotReward(slot, entry.itemId(), entry.count()));
        }
        return out;
    }
}

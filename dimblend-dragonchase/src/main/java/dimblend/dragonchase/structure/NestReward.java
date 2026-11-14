package dimblend.dragonchase.structure;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/**
 * Writes the nest's kill rewards into an assembled nest (requirements.md §3.6, decision
 * reward-blocks): twenty storage blocks into the twenty {@code 矿} cells and a shulker box with the
 * 27-slot list into the {@code 箱} cell.
 *
 * <p><b>The tables and the shuffles are elsewhere.</b> {@link NestRewardTable} holds them without a
 * single Minecraft type so they can be unit-tested; this class only turns cell offsets into plot
 * positions and item ids into stacks.
 *
 * <p><b>Missing mods skip a slot, loudly once.</b> The shulker list names items from mods this module
 * does not depend on (kaleidoscope, voidscape). A missing item is not a crash: that slot stays empty
 * and one WARN names the item.
 *
 * <p><b>Plot writes, then a relight.</b> A reward block is written at its plot position — the same
 * path a player placing a block on the car takes — and the plot is relit afterwards, because a
 * plot's light engine never learns about writes made from the parent level (see {@link PlotRelight}).
 */
public final class NestReward {

    /** Item ids already reported missing, so the log gets one line per item, not per nest. */
    private static final Set<String> REPORTED_MISSING = new HashSet<>();

    private NestReward() {
    }

    /** Writes both rewards into an assembled nest. */
    public static void place(ServerLevel level, ServerSubLevel nest, long seed) {
        CarPlans.Plan plan = CarPlans.of(CarKind.NEST);
        for (NestRewardTable.OreReward reward : NestRewardTable.oreRewards(seed)) {
            BlockPos pos = CarMotion.plotPos(nest, reward.cell());
            level.setBlock(pos, CarBlocks.state(reward.blockId()), Block.UPDATE_ALL);
        }
        CarPlans.Cell chest = plan.chestCell();
        if (chest == null) {
            return;
        }
        BlockPos chestPos = CarMotion.plotPos(nest, chest);
        level.setBlock(chestPos, CarBlocks.state(CarBlocks.SHULKER_BOX), Block.UPDATE_ALL);
        fillShulker(level, chestPos, seed);
        // One relight at the end, for the whole plot: the 11 x 7 nest straddles plot chunk borders
        // once it is placed anywhere but the chunk centre, so a single-chunk pass would leave part of
        // the reward blocks black in a plot whose light engine never saw the writes.
        PlotRelight.relight(level, nest);
    }

    /** Stocks the shulker box, skipping item ids whose mod is absent. */
    private static void fillShulker(ServerLevel level, BlockPos pos, long seed) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) {
            DimBlendDragonChase.LOGGER.warn("Nest shulker box at {} has no container block entity", pos);
            return;
        }
        container.clearContent();
        for (NestRewardTable.SlotReward reward : NestRewardTable.slotRewards(seed)) {
            Item item = item(reward.itemId());
            if (item == null) {
                continue;
            }
            int slot = reward.slot();
            if (slot < 0 || slot >= container.getContainerSize()) {
                continue;
            }
            container.setItem(slot, new ItemStack(item, reward.count()));
        }
    }

    /** The item for an id, or {@code null} after one WARN when the mod that owns it is absent. */
    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null && REPORTED_MISSING.add(id)) {
            DimBlendDragonChase.LOGGER.warn("Nest shulker box skips {}: no such item (mod not installed?)", id);
        }
        return item;
    }
}

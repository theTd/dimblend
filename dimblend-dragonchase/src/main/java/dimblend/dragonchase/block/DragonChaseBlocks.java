package dimblend.dragonchase.block;

import dimblend.dragonchase.DimBlendDragonChase;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The blocks this module owns.
 *
 * <p>Only the crystal car's pedestal is registered here; every other block a car is built from is
 * vanilla or belongs to another DimBlend module and is reached by id ({@code CarBlocks}). Keeping the
 * one owned block in one class means the module constructor stays pure wiring
 * ({@code DragonChaseBlocks.register(modEventBus)}) and no other class touches a deferred holder
 * before the registries are frozen.
 */
public final class DragonChaseBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DimBlendDragonChase.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DimBlendDragonChase.MODID);

    /**
     * The crying-obsidian soul campfire under each end crystal.
     *
     * <p>Vanilla's campfire numbers: hardness 2.0, light 10 while lit, the 7/16-high shape. The sound
     * is stone because the visible material is crying obsidian; the block itself carries no fire
     * damage ({@link ObsidianSoulCampfireBlock}).
     */
    public static final DeferredBlock<ObsidianSoulCampfireBlock> OBSIDIAN_SOUL_CAMPFIRE =
            BLOCKS.register("obsidian_soul_campfire", () -> new ObsidianSoulCampfireBlock(
                    BlockBehaviour.Properties.of()
                            .strength(2.0F)
                            .sound(SoundType.STONE)
                            .lightLevel(state -> state.getValue(ObsidianSoulCampfireBlock.LIT) ? 10 : 0)
                            .noOcclusion()));

    /** The pedestal as an item, so the block is placeable and pick-block returns something. */
    public static final DeferredItem<BlockItem> OBSIDIAN_SOUL_CAMPFIRE_ITEM =
            ITEMS.registerSimpleBlockItem("obsidian_soul_campfire", OBSIDIAN_SOUL_CAMPFIRE);

    /** Registers both deferred registers on the mod event bus. Call once from the module constructor. */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }

    private DragonChaseBlocks() {
    }
}

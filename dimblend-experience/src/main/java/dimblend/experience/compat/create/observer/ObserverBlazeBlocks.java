package dimblend.experience.compat.create.observer;

import dimblend.experience.DimBlend;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry entries of the observer blaze: one block (no BlockItem: the mode is entered on a placed burner and
 * left again, never carried), and its block entity type. Registered only while Create and Sable are loaded,
 * because the block reuses Create's heater shape and blaze renderer and only ever exists on a Sable vehicle.
 *
 * <p>The block entity type resolves its block through {@link #OBSERVER_BLAZE}, so the supplier indirection keeps
 * static-initialisation order safe: the block is registered before the type supplier runs.</p>
 */
public final class ObserverBlazeBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DimBlend.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlend.MODID);

    /**
     * Properties copied from Create's blaze burner: {@code Properties.of().mapColor(COLOR_GRAY)} plus a fixed
     * light level, with no strength override (the burner declares none either). The light level is a smouldering
     * burner's 8, which is what a stock keeper burner shows in game; Create's own 15 is tied to a heat state this
     * block does not have.
     */
    public static final DeferredBlock<ObserverBlazeBlock> OBSERVER_BLAZE = BLOCKS.registerBlock("observer_blaze",
            ObserverBlazeBlock::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).lightLevel(state -> 8));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ObserverBlazeBlockEntity>> OBSERVER_BLAZE_ENTITY =
            BLOCK_ENTITIES.register("observer_blaze",
                    () -> BlockEntityType.Builder.of(ObserverBlazeBlockEntity::new, OBSERVER_BLAZE.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private ObserverBlazeBlocks() {
    }
}

package dimblend.experience.compat.dolls;

import com.simibubi.create.AllBlocks;
import dimblend.experience.DimBlend;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Placed-seat variants only: deliberately no BlockItems or recipes. Load behind the Create gate. */
public final class DollKeeperSeats {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DimBlend.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlend.MODID);
    private static final Map<DyeColor, DeferredBlock<DollKeeperSeatBlock>> SEATS = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor color : DyeColor.values()) {
            SEATS.put(color, BLOCKS.register(color.getName() + "_doll_keeper_seat",
                    () -> new DollKeeperSeatBlock(BlockBehaviour.Properties.ofFullCopy(AllBlocks.SEATS.get(color).get()), color)));
        }
    }

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DollKeeperSeatBlockEntity>> BLOCK_ENTITY =
            BLOCK_ENTITIES.register("doll_keeper_seat", () -> BlockEntityType.Builder.of(
                    DollKeeperSeatBlockEntity::new, SEATS.values().stream().map(DeferredBlock::get).toArray(Block[]::new))
                    .build(null));

    public static DollKeeperSeatBlock seat(DyeColor color) {
        return SEATS.get(color).get();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        DollStockKeeperInteraction.register();
    }

    private DollKeeperSeats() {}
}

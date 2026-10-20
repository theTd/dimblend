package dimblend.experience.chassis;

import com.crystaelix.simurail.api.bogey.BogeyRenderedType;
import com.crystaelix.simurail.compat.railways.RailwaysBogeys;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Registry-based access to dimblend-blocks; no imports across its implementation boundary. */
public final class ChassisBlocks {
    private ChassisBlocks() {}

    public static BlockState state(ChassisBlueprint.Cell cell) {
        return switch (cell.symbol()) {
            case 'C' -> block("simurail:automatic_coupler").setValue(BlockStateProperties.HORIZONTAL_FACING,
                    cell.x() < 0 ? Direction.WEST : Direction.EAST);
            case 'B' -> block("dimblend_blocks:creative_copycat_beam").setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            case 'H', 'D', 'N' -> block(cell.symbol() == 'N' ? "dimblend_blocks:creative_clean_copycat_slab"
                    : "dimblend_blocks:creative_copycat_slab")
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                    .setValue(BlockStateProperties.SLAB_TYPE, cell.symbol() == 'D' ? SlabType.DOUBLE : SlabType.TOP);
            // Create's UP shape occupies the bottom 3 pixels; DOWN puts the deck at the top.
            case 'P' -> block("dimblend_blocks:creative_copycat_panel").setValue(BlockStateProperties.FACING, Direction.DOWN);
            case 'S' -> block("dimblend_blocks:creative_shaft").setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            case 'W', 'I' -> block("simurail:physics_bogey").setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            default -> throw new IllegalArgumentException("Unknown chassis symbol " + cell.symbol());
        };
    }

    private static BlockState block(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        Block block = BuiltInRegistries.BLOCK.getOptional(key)
                .orElseThrow(() -> new IllegalStateException("Missing chassis block " + key));
        return block.defaultBlockState();
    }

    public static void configureBogey(ServerLevel level, BlockPos pos, char symbol) {
        if (symbol != 'W' && symbol != 'I') return;
        if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
            throw new IllegalStateException("Missing chassis bogey at " + pos);
        }
        var options = bogey.getOptions();
        options.type = new BogeyRenderedType(symbol == 'W'
                ? RailwaysBogeys.WIDE_STANDARD_SMALL.type() : RailwaysBogeys.INVISIBLE.type());
        bogey.setOptions(options);
        bogey.setChanged();
        bogey.sendData();
    }
}

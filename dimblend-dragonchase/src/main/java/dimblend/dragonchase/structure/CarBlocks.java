package dimblend.dragonchase.structure;

import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.ICopycatBlockEntity;
import com.crystaelix.simurail.api.bogey.BogeyRenderedType;
import com.crystaelix.simurail.api.bogey.BogeyType;
import com.crystaelix.simurail.api.track.TrackTypeOverrides;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyControlMode;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial.TrackType;
import dimblend.dragonchase.DimBlendDragonChase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Every block id, block state and block-entity value the three cars use.
 *
 * <p><b>No imports across a module boundary.</b> {@code dimblend-blocks} is reached by id, the way
 * {@code ChassisBlocks} and {@code RewardWagonBlocks} do it, so this module still builds when those
 * classes are absent. {@code dimblend-blocks} registers the creative copycats only when Copycats+ is
 * loaded ({@code CreativeCopycats#register}), which is why a missing id is a hard error here rather
 * than a silent hole.
 *
 * <p><b>The copycat blocks are survival-unbreakable.</b> Verified in {@code dimblend-blocks}:
 * {@code CreativeCopycats#creativeProps} gives every creative variant
 * {@code strength(-1.0F, 3600000.0F)} and no loot table, i.e. hardness -1 (a survival player can
 * never break them) and blast resistance 3.6M (an explosion cannot either). That is what the
 * nest's "no extra edit lock" decision relies on (requirements.md §3.3 → nest-protection). The
 * couplers and the bogey are Simurail's and are ordinary blocks; the runtime rules protect those.
 *
 * <p><b>The slab material lives in the block entity.</b> A clean copycat double slab has no
 * material property: each half ({@code top} / {@code bottom}) stores its own material in
 * {@code material_data}. Writing the block state alone leaves the slab invisible, so
 * {@link #dress} must run <i>after</i> the block is in the level.
 */
public final class CarBlocks {

    /** {@code simurail:automatic_coupler}; the existing chassis and wagons use the same block. */
    public static final String COUPLER = "simurail:automatic_coupler";
    /** {@code simurail:physics_bogey}. */
    public static final String BOGEY = "simurail:physics_bogey";
    /** The creative copycat beam ({@code CreativeCopycats#CREATIVE_COPYCAT_BEAM}). */
    public static final String BEAM = "dimblend_blocks:creative_copycat_beam";
    /**
     * The clean creative copycat slab. Identical behaviour to {@code creative_copycat_slab} but
     * registered under its own block entity type, so the wash-car behaviour cannot bind to it —
     * these cars are towed through the end dimension and must never collect dirt
     * ({@code CreativeCopycats} C4 comment).
     */
    public static final String SLAB = "dimblend_blocks:creative_clean_copycat_slab";

    public static final String PURPUR = "minecraft:purpur_block";
    public static final String OBSIDIAN = "minecraft:obsidian";
    public static final String REDSTONE_ORE = "minecraft:redstone_ore";
    public static final String DEEPSLATE_REDSTONE_ORE = "minecraft:deepslate_redstone_ore";
    public static final String END_STONE_BRICKS = "minecraft:end_stone_bricks";
    public static final String REDSTONE_BLOCK = "minecraft:redstone_block";
    public static final String REDSTONE_LAMP = "minecraft:redstone_lamp";
    public static final String SHULKER_BOX = "minecraft:shulker_box";

    /** The stress the requirement pins on all three bogeys: 0.0 (they are pushed, never driven). */
    public static final float BOGEY_STRESS = 0.0F;

    private CarBlocks() {
    }

    /**
     * The block state for one cell, or {@code null} when the cell is a hole or empty.
     *
     * @throws IllegalStateException when a required block id is missing from the registry
     */
    public static BlockState stateFor(CarPlans.Cell cell) {
        return switch (cell.kind()) {
            case AIR, ORE, CHEST -> null;
            case COUPLER -> state(COUPLER)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING,
                            cell.dx() < 0 ? Direction.WEST : Direction.EAST)
                    // A powered coupler never couples (AutomaticCouplerBlockEntity#sable$physicsTick:
                    // findPartner only runs while !isPowered()). Nothing powers these, and setting it
                    // explicitly documents that.
                    .setValue(BlockStateProperties.POWERED, false)
                    .setValue(BlockStateProperties.WATERLOGGED, false);
            case BOGEY -> state(BOGEY).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            case BEAM -> state(BEAM).setValue(BlockStateProperties.AXIS, Direction.Axis.X);
            case PURPUR_SLAB, OBSIDIAN_SLAB, REDSTONE_ORE_SLAB, END_STONE_SLAB ->
                    state(SLAB).setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                            .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
            case REDSTONE_BLOCK -> state(REDSTONE_BLOCK);
            case REDSTONE_LAMP -> state(REDSTONE_LAMP);
        };
    }

    /**
     * Dresses a cell's block entity once the block is in the level: the copycat materials, the
     * bogey options, and the coupler's partner-independent defaults.
     */
    public static void dress(ServerLevel level, BlockPos pos, CarPlans.Cell cell) {
        switch (cell.kind()) {
            case BEAM -> dressBeam(level, pos);
            case PURPUR_SLAB -> dressSlab(level, pos, state(PURPUR), state(PURPUR));
            case OBSIDIAN_SLAB -> dressSlab(level, pos, state(OBSIDIAN), state(OBSIDIAN));
            case REDSTONE_ORE_SLAB -> dressSlab(level, pos, state(REDSTONE_ORE), state(DEEPSLATE_REDSTONE_ORE));
            case END_STONE_SLAB -> dressSlab(level, pos, state(END_STONE_BRICKS), state(END_STONE_BRICKS));
            case BOGEY -> prepareBogey(level, pos);
            case COUPLER -> prepareCoupler(level, pos);
            default -> {
            }
        }
    }

    /**
     * Applies the requirement's bogey options: enabled, no angular offsets, no linear offsets, no
     * vertical movement, {@code BRAKING} control, stress 0.0, both steering connectors visible.
     *
     * <p>{@code setAngularType(0)} turns yaw and pitch offsets off together and
     * {@code setLinearType(0)} does the same for the vertical and lateral ones
     * ({@code PhysicsBogeyOptions#setAngularType/#setLinearType}); {@code BRAKING} makes the
     * bogey's brake strength follow the redstone signal it reads from the block above it.
     */
    public static void prepareBogey(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
            throw new IllegalStateException("Dragon Chase car bogey missing at " + pos);
        }
        var options = bogey.getOptions();
        options.type = new BogeyRenderedType(bogeyTypeFor(level, pos));
        options.enabled = true;
        options.setAngularType(0);
        options.setLinearType(0);
        options.allowVerticalMovement = false;
        options.controlMode = PhysicsBogeyControlMode.BRAKING;
        options.setStress(BOGEY_STRESS);
        options.renderFrontConnector = true;
        options.renderBackConnector = true;
        // setOptions copies field by field, pokes both axles and sends the data packet.
        bogey.setOptions(options);
    }

    /**
     * Picks Simurail's registered default bogey type for the track gauge directly beneath
     * {@code bogeyPos} ({@code RailwaysBogeys.WIDE_STANDARD_SMALL} on {@code railways:track_*_wide}
     * in TrainTripWorld, or {@link BogeyType#getFallback()} on standard {@code create:track} when
     * Steam 'n' Rails is absent), without importing {@code railways} at compile time.
     */
    private static BogeyType bogeyTypeFor(ServerLevel level, BlockPos bogeyPos) {
        BlockState below = level.getBlockState(bogeyPos.below());
        if (below.getBlock() instanceof ITrackBlock track) {
            TrackType trackType = TrackTypeOverrides.getTrackType(track.getMaterial());
            return BogeyType.getDefault(trackType, false);
        }
        return BogeyType.getFallback();
    }

    /**
     * Prepares an {@code automatic_coupler} so both ends of the car can connect.
     *
     * <p>Nothing has to be written: the block entity builds its own joint on its first physics
     * tick and finds a partner within 0.141 blocks of its end position
     * ({@code AutomaticCouplerBlockEntity#findPartner}, {@code distSq < 0.02}) as long as neither
     * side is powered and the coupler lengths match. Both conditions are handled by
     * {@link #stateFor} (default length, {@code POWERED=false}); pre-setting a partner would point
     * at a block that is about to move into a sub-level.
     */
    public static void prepareCoupler(ServerLevel level, BlockPos pos) {
        // Intentionally nothing; see the javadoc.
    }

    /** Dresses one half of a clean copycat double slab. */
    private static void dressSlab(ServerLevel level, BlockPos pos, BlockState top, BlockState bottom) {
        if (!(level.getBlockEntity(pos) instanceof IMultiStateCopycatBlockEntity copycat)) {
            return;
        }
        // A double slab has two parts; both must be dressed or one half stays invisible
        // (RewardWagonBlocks#dressAsIndustrialIron does the same for the reward wagon).
        copycat.setMaterial(SlabType.TOP.getSerializedName(), top);
        copycat.setMaterial(SlabType.BOTTOM.getSerializedName(), bottom);
    }

    /** Dresses a copycat beam; the beam block entity holds one material for the whole block. */
    private static void dressBeam(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ICopycatBlockEntity copycat) {
            copycat.setMaterial(state(PURPUR));
        }
    }

    /** Resolves a block by id, failing loudly: a missing id means the car would be a hole. */
    public static BlockState state(String id) {
        return block(id).defaultBlockState();
    }

    public static Block block(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.getOptional(key)
                .orElseThrow(() -> new IllegalStateException("Missing Dragon Chase car block " + key));
    }

    /** True when the level holds every block these cars are built from. */
    public static boolean available() {
        for (String id : new String[] {COUPLER, BOGEY, BEAM, SLAB, PURPUR, OBSIDIAN, REDSTONE_ORE,
                DEEPSLATE_REDSTONE_ORE, END_STONE_BRICKS, REDSTONE_BLOCK, REDSTONE_LAMP, SHULKER_BOX}) {
            if (BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).isEmpty()) {
                DimBlendDragonChase.LOGGER.warn("Dragon Chase cars disabled: missing block {}", id);
                return false;
            }
        }
        return true;
    }
}

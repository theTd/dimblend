package dimblend.experience.reward;

import com.crystaelix.simurail.api.bogey.BogeyRenderedType;
import com.crystaelix.simurail.compat.railways.RailwaysBogeys;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyControlMode;
import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity;
import dimblend.experience.DimBlend;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * Registry lookups for the reward wagon, kept in one place so the builder above it never
 * talks to a registry.
 *
 * <p><b>No imports across a module boundary.</b> {@code dimblend-blocks} and
 * {@code dimblend} are reached by id, the same way {@code ChassisBlocks} does it, because
 * this module must build even when those classes are absent at runtime.
 *
 * <p><b>The slab's material lives in its block entity.</b> The clean copycat slab has no
 * material property; the industrial-iron look is stored per part ({@code top} /
 * {@code bottom}) in {@code material_data}. Writing the state alone leaves it invisible,
 * so {@link #dressAsIndustrialIron} must run after the block is in the level.
 */
public final class RewardWagonBlocks {
    public static final String COPYCAT_SLAB = "dimblend_blocks:creative_clean_copycat_slab";
    public static final String COUPLER = "simurail:automatic_coupler";
    public static final String BOGEY = "simurail:physics_bogey";
    public static final String INDUSTRIAL_IRON = "create:industrial_iron_block";
    public static final String CREATIVE_MOTOR = "create:creative_motor";
    /**
     * The clutch the requirement calls "翻转离合器". The player confirmed this id: Create's own
     * {@code create:clutch} is the opposite sense — it disconnects when powered — whereas this
     * one stays disconnected until powered, which is what makes a fresh wagon stand still.
     */
    public static final String CLUTCH = "create_connected:inverted_clutch";
    public static final String LEVER = "minecraft:lever";
    /** The rotation speed the requirement pins the motor at, and its ceiling in Create. */
    public static final int MOTOR_SPEED = 256;
    public static final String TWILIGHT_CHEST = "twilightforest:twilight_oak_chest";
    public static final String SCULK_SENSOR = "minecraft:sculk_sensor";
    public static final String TNT = "minecraft:tnt";
    /** The rotating dimension's gate block; a wagon reaching one dissolves. */
    public static final String WARP_GATE = "dimblend:warp_gate";
    /** The stress the requirement asks for on the bogey. */
    public static final float BOGEY_STRESS = 32.0F;

    private RewardWagonBlocks() {
    }

    /** The block for one deck cell; air cells return {@code null}. */
    public static BlockState deckState(RewardWagonLayout.Cell cell) {
        return switch (cell.kind()) {
            case AIR -> null;
            case COUPLER -> state(couplerId()).setValue(BlockStateProperties.HORIZONTAL_FACING,
                    cell.dx() < 0 ? Direction.WEST : Direction.EAST);
            case BOGEY -> state(BOGEY).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            case SLAB -> slab(SlabType.TOP);
            case DOUBLE_SLAB -> slab(SlabType.DOUBLE);
        };
    }

    private static BlockState slab(SlabType type) {
        return state(COPYCAT_SLAB)
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                .setValue(BlockStateProperties.SLAB_TYPE, type);
    }

    /** Resolves a block by id, failing loudly: a missing id means the wagon would be a hole. */
    public static BlockState state(String id) {
        return block(id).defaultBlockState();
    }

    public static Block block(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        return BuiltInRegistries.BLOCK.getOptional(key)
                .orElseThrow(() -> new IllegalStateException("Missing reward wagon block " + key));
    }

    public static BlockState oreState(RewardWagonContent content) {
        return state(content.oreBlock());
    }

    public static BlockState rawState(RewardWagonContent content) {
        return state(content.rawBlock());
    }

    /**
     * The coupler block id, chosen so both ends of the wagon can connect: the requirement
     * asks for {@code 勾} at both ends and the existing chassis uses the same block.
     */
    private static String couplerId() {
        return COUPLER;
    }

    /**
     * Sets an {@code automatic_coupler}'s partner-independent defaults.
     *
     * <p>Deliberately does nothing beyond existing: the block entity builds its own joint
     * on its first tick, and pre-setting a partner would point at a block that is about to
     * be moved into a sub-level.
     */
    public static void prepareCoupler(ServerLevel level, BlockPos pos) {
        // Nothing to configure yet; the block entity's own tick does the work.
    }

    /**
     * Applies the requirement's bogey options: enabled, no rotation, no offset, no vertical
     * movement, braking control, stress 32, connector visible.
     *
     * <p>The stress is already at the top of simurail's clamp (0..32), which is exactly what
     * the requirement's {@code 32.0} means — this wagon is meant to be shoved around by
     * whatever is coupled to it, not to drive itself.
     */
    public static void prepareBogey(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey)) {
            throw new IllegalStateException("Reward wagon bogey missing at " + pos);
        }
        var options = bogey.getOptions();
        options.type = new BogeyRenderedType(RailwaysBogeys.WIDE_STANDARD_SMALL.type());
        options.enabled = true;
        options.setAngularType(0);
        options.setLinearType(0);
        options.allowVerticalMovement = false;
        options.controlMode = PhysicsBogeyControlMode.BRAKING;
        options.setStress(BOGEY_STRESS);
        options.renderFrontConnector = true;
        options.renderBackConnector = true;
        bogey.setOptions(options);
        bogey.setChanged();
        bogey.sendData();
    }

    /**
     * Dresses a clean copycat slab as industrial iron.
     *
     * <p>Must run after the block is placed: the setter reads the level to de-duplicate
     * neighbours and to push the render update, and it calls {@code setChanged} plus
     * {@code sendBlockUpdated} itself.
     */
    public static void dressAsIndustrialIron(ServerLevel level, BlockPos pos, BlockState slabState) {
        if (!(level.getBlockEntity(pos) instanceof IMultiStateCopycatBlockEntity copycat)) {
            return;
        }
        BlockState material = state(INDUSTRIAL_IRON);
        if (slabState.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            // A double slab has two parts; both must be dressed or one half stays invisible.
            copycat.setMaterial(SlabType.TOP.getSerializedName(), material);
            copycat.setMaterial(SlabType.BOTTOM.getSerializedName(), material);
        } else {
            copycat.setMaterial(SlabType.TOP.getSerializedName(), material);
        }
    }

    /** Drops for one block, resolved through the level's loot tables so TNT and ores behave. */
    public static List<ItemStack> drops(ServerLevel level, BlockPos pos, BlockState blockState) {
        LootParams.Builder params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withParameter(LootContextParams.BLOCK_STATE, blockState);
        return blockState.getDrops(params);
    }

    /** Guard so a missing optional mod is one clear log line, not a stack trace per site. */
    public static boolean available() {
        for (String id : new String[] {COPYCAT_SLAB, COUPLER, BOGEY, INDUSTRIAL_IRON, CREATIVE_MOTOR,
                TWILIGHT_CHEST, SCULK_SENSOR, TNT,
                "eternal_starlight:grimstone_deepsilver_ore", "eternal_starlight:raw_deepsilver_block",
                "eternal_starlight:grimstone_saltpeter_ore", "eternal_starlight:saltpeter_block"}) {
            if (BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id)).isEmpty()) {
                DimBlend.LOGGER.warn("Reward wagon disabled: missing block {}", id);
                return false;
            }
        }
        return true;
    }
}
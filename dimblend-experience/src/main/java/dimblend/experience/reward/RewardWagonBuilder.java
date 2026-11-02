package dimblend.experience.reward;

import com.simibubi.create.AllSoundEvents;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.experience.DimBlend;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Builds one reward wagon into the world and turns it into a Sable sub-level.
 *
 * <p><b>The blocks are written here, not by worldgen.</b> A wagon needs block entities
 * with configured data — bogeys, copycat materials, a stocked chest — and Sable assembly,
 * all of which belong on the server thread with players present. This is the same shape
 * the station depot's chassis use: the site is decided deterministically and the build
 * happens the first time somebody is close enough.
 *
 * <p><b>No force-load ticket.</b> The requirement says the wagon must not be force-loaded,
 * and Sable's assembly helper adds no ticket of its own, so simply not calling
 * {@code addForceLoadTicket} is the whole implementation. The consequence is documented
 * where it matters: the wagon is serialised with its chunk and rebuilt on load, and its
 * dissolve check is paused while the chunk is unloaded.
 *
 * <p><b>Rollback on failure.</b> Every write is remembered; if anything throws, the blocks
 * this build placed are removed again so a retry starts from a clean track.
 */
public final class RewardWagonBuilder {
    /** Rails are at y = 64 in the corridor; the deck sits one above them. */
    public static final int TRACK_Y = 64;

    /**
     * The herbs of the requirement's twilight chest: 4 to 16 draws of one food item each,
     * stack size 1, into random slots.
     */
    public static final int CHEST_MIN_DRAWS = 4;
    public static final int CHEST_MAX_DRAWS = 16;

    private RewardWagonBuilder() {
    }

    /** Centre block of the wagon, from its centre X on the corridor line. */
    public static BlockPos center(int centerX) {
        return new BlockPos(centerX, TRACK_Y + RewardWagonLayout.STRUCTURE_DY, 0);
    }

    /**
     * Builds and assembles one wagon.
     *
     * @return the sub-level, or {@code null} when the site could not be built (in which
     *         case the blocks were rolled back)
     */
    public static dev.ryanhcode.sable.sublevel.ServerSubLevel build(ServerLevel level, RewardWagonPlan.Wagon wagon) {
        BlockPos center = center(wagon.centerX());
        Map<BlockPos, BlockState> placed = new HashMap<>();
        try {
            writeDeck(level, center, placed);
            writeContent(level, center, wagon, placed);
            dev.ryanhcode.sable.sublevel.ServerSubLevel car = assemble(level, center, placed);
            if (car == null) {
                rollback(level, placed);
                return null;
            }
            return car;
        } catch (RuntimeException failure) {
            DimBlend.LOGGER.warn("Reward wagon at {} failed: {}", center, failure.toString());
            rollback(level, placed);
            return null;
        }
    }

    /** Writes the 23 structural blocks and dresses the copycat slabs. */
    private static void writeDeck(ServerLevel level, BlockPos center, Map<BlockPos, BlockState> placed) {
        for (RewardWagonLayout.Cell cell : RewardWagonLayout.structure()) {
            BlockState state = RewardWagonBlocks.deckState(cell);
            if (state == null) {
                continue;
            }
            BlockPos pos = offset(center, cell.dx(), 0, cell.dz());
            place(level, pos, state, placed);
            switch (cell.kind()) {
                case SLAB, DOUBLE_SLAB -> RewardWagonBlocks.dressAsIndustrialIron(level, pos, state);
                case BOGEY -> RewardWagonBlocks.prepareBogey(level, pos);
                case COUPLER -> RewardWagonBlocks.prepareCoupler(level, pos);
                default -> {
                }
            }
        }
    }

    /** Writes whatever this wagon carries, on the content layer. */
    private static void writeContent(ServerLevel level, BlockPos center, RewardWagonPlan.Wagon wagon,
            Map<BlockPos, BlockState> placed) {
        RewardWagonContent content = wagon.content();
        BlockPos bogeyTop = offset(center, RewardWagonLayout.bogey().dx(),
                RewardWagonLayout.CONTENT_DY - RewardWagonLayout.STRUCTURE_DY, 0);
        if (content.isOre()) {
            writeOre(level, center, content, wagon.seed(), placed);
            return;
        }
        switch (content) {
            case TWILIGHT_CHEST -> {
                place(level, bogeyTop, RewardWagonBlocks.state(RewardWagonBlocks.TWILIGHT_CHEST), placed);
                stockChest(level, bogeyTop, level.getRandom());
            }
            case SOUND_TRAP -> writeSoundTrap(level, bogeyTop, placed);
            case MOTOR_WAGON -> writeMotor(level, center, placed);
            default -> {
                // NONE never reaches the builder; a wagon always carries something.
            }
        }
    }

    private static void writeOre(ServerLevel level, BlockPos center, RewardWagonContent content, long seed,
            Map<BlockPos, BlockState> placed) {
        RewardWagonOre.Pattern pattern = RewardWagonOre.build(seed);
        BlockState ore = RewardWagonBlocks.oreState(content);
        BlockState raw = RewardWagonBlocks.rawState(content);
        int contentDy = RewardWagonLayout.CONTENT_DY - RewardWagonLayout.STRUCTURE_DY;
        for (RewardWagonOre.Cell cell : pattern.cells()) {
            BlockPos pos = offset(center, cell.dx(), contentDy, cell.dz());
            switch (cell.fill()) {
                case AIR -> {
                    // Deliberately nothing: the corridor already carved this cell out.
                }
                case ORE -> place(level, pos, ore, placed);
                case RAW -> {
                    place(level, pos, raw, placed);
                    place(level, pos.above(), ore, placed);
                }
            }
        }
    }

    private static void writeSoundTrap(ServerLevel level, BlockPos sensorPos, Map<BlockPos, BlockState> placed) {
        place(level, sensorPos, RewardWagonBlocks.state(RewardWagonBlocks.SCULK_SENSOR), placed);
        BlockState tnt = RewardWagonBlocks.state(RewardWagonBlocks.TNT);
        for (Direction direction : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            place(level, sensorPos.relative(direction), tnt, placed);
        }
    }

    /**
     * The motor wagon: the creative motor replaces the deck cell west of the bogey, so its
     * shaft faces the bogey along X. The cell's slab is removed first — the requirement
     * says the wagon is empty apart from the motor, and a slab and a motor cannot share one
     * cell.
     */
    private static void writeMotor(ServerLevel level, BlockPos center, Map<BlockPos, BlockState> placed) {
        RewardWagonLayout.MotorParts parts = RewardWagonLayout.motorParts();
        BlockPos motorPos = offset(center, parts.motor().dx(), 0, parts.motor().dz());
        BlockPos clutchPos = offset(center, parts.clutch().dx(), 0, parts.clutch().dz());
        BlockPos leverPos = offset(center, parts.lever().dx(), RewardWagonLayout.LEVER_DY, parts.lever().dz());

        // The motor faces east so its shaft points at the clutch, which points at the bogey.
        place(level, motorPos, RewardWagonBlocks.state(RewardWagonBlocks.CREATIVE_MOTOR)
                .setValue(BlockStateProperties.FACING, Direction.EAST), placed);
        pinMotorSpeed(level, motorPos);

        // A clutch is an axis block, not a directional one.
        place(level, clutchPos, RewardWagonBlocks.state(RewardWagonBlocks.CLUTCH)
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X), placed);

        // Lever on the clutch's upper face, pointing west, not pulled.
        place(level, leverPos, RewardWagonBlocks.state(RewardWagonBlocks.LEVER)
                .setValue(BlockStateProperties.ATTACH_FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.POWERED, false), placed);
    }

    /** Pins the creative motor's stored speed at the requirement's 256 rpm. */
    private static void pinMotorSpeed(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos)
                instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity motor)) {
            throw new IllegalStateException("Creative motor block entity missing at " + pos);
        }
        motor.generatedSpeed.value = RewardWagonBlocks.MOTOR_SPEED;
        motor.setChanged();
    }

    /**
     * Stocks the twilight chest.
     *
     * <p>The requirement asks for a random item from "the twilight forest: food" category,
     * four to sixteen draws of one item each at stack size one, placed into random slots.
     * The category is a creative tab, which has no server-side lookup, so the equivalent
     * test is used: the item's namespace is {@code twilightforest} and it carries a food
     * component. Twilight Forest's own code tests food the same way.
     *
     * <p>Draws may repeat an item; each lands in its own slot, and the loop stops early only
     * if the chest runs out of empty slots, which 27 slots versus 16 draws never does.
     */
    private static void stockChest(ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity chest)) {
            throw new IllegalStateException("Twilight chest has no container block entity at " + pos);
        }
        List<ItemStack> foods = twilightFoods();
        if (foods.isEmpty()) {
            throw new IllegalStateException("No twilight forest food items are registered");
        }
        int draws = CHEST_MIN_DRAWS + random.nextInt(CHEST_MAX_DRAWS - CHEST_MIN_DRAWS + 1);
        chest.clearContent();
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            slots.add(slot);
        }
        for (int draw = 0; draw < draws && !slots.isEmpty(); draw++) {
            int slotIndex = random.nextInt(slots.size());
            int slot = slots.remove(slotIndex);
            ItemStack stack = foods.get(random.nextInt(foods.size())).copy();
            stack.setCount(1);
            chest.setItem(slot, stack);
        }
    }

    /** Every registered item that is a Twilight Forest food. */
    static List<ItemStack> twilightFoods() {
        List<ItemStack> out = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (!"twilightforest".equals(key.getNamespace())) {
                continue;
            }
            ItemStack stack = new ItemStack(item);
            if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
                out.add(stack);
            }
        }
        return out;
    }

    /** Turns the placed blocks into one sub-level, with no loading ticket. */
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel assemble(ServerLevel level, BlockPos center,
            Map<BlockPos, BlockState> placed) {
        int halfLength = RewardWagonLayout.HALF_LENGTH;
        int halfWidth = RewardWagonLayout.HALF_WIDTH;
        BoundingBox3i bounds = new BoundingBox3i(
                center.getX() - halfLength, center.getY(), center.getZ() - halfWidth,
                center.getX() + halfLength, center.getY() + (RewardWagonLayout.ORE_TOP_DY
                        - RewardWagonLayout.STRUCTURE_DY), center.getZ() + halfWidth);
        var car = SubLevelAssemblyHelper.assembleBlocks(level, center, placed.keySet(), bounds);
        if (car == null || car.isRemoved()) {
            return null;
        }
        // Intentionally no SubLevelContainer.addForceLoadTicket here: the requirement says
        // this wagon is not force-loaded.
        return car;
    }

    /** Puts back exactly the blocks this build wrote, and nothing else. */
    private static void rollback(ServerLevel level, Map<BlockPos, BlockState> placed) {
        for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
            if (level.getBlockState(entry.getKey()).equals(entry.getValue())) {
                level.setBlock(entry.getKey(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        placed.clear();
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state, Map<BlockPos, BlockState> placed) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
        placed.put(pos.immutable(), state);
    }

    private static BlockPos offset(BlockPos center, int dx, int dy, int dz) {
        return center.offset(dx, dy, dz);
    }

    /** Plays the requirement's dissolve feedback at one spot. */
    public static void playDissolveEffects(ServerLevel level, BlockPos bogeyPos) {
        var random = level.getRandom();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                bogeyPos.getX() + 0.5, bogeyPos.getY() + 0.5, bogeyPos.getZ() + 0.5,
                20, 0.5, 0.5, 0.5, 0.02);
        AllSoundEvents.WRENCH_REMOVE.playOnServer(level, bogeyPos, 1.0F, 1.0F);
    }

    /** True when the dimension is the rotating corridor this feature belongs to. */
    public static boolean inDimension(ServerLevel level) {
        return level.dimension().location().toString().equals("dimblend:rotating");
    }
}
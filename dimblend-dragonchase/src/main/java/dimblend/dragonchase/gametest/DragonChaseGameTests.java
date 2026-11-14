package dimblend.dragonchase.gametest;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyControlMode;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.api.CorridorRails;
import dimblend.api.NonCrewCarriages;
import dimblend.api.RegionLanes;
import dimblend.api.RegionOverrides;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.DragonChaseRegistries;
import dimblend.dragonchase.carriage.CarRules;
import dimblend.dragonchase.carriage.DragonCarriages;
import dimblend.dragonchase.chase.ChaseDirector;
import dimblend.dragonchase.chase.DragonChaseSavedData;
import dimblend.dragonchase.entity.ChaseDragon;
import dimblend.dragonchase.entity.ChaseDragonHandle;
import dimblend.dragonchase.entity.ChaseFireball;
import dimblend.dragonchase.structure.CarBlocks;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.CarMotion;
import dimblend.dragonchase.structure.CarPlans;
import dimblend.dragonchase.structure.DragonCars;
import dimblend.dragonchase.structure.NestRewardTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.jetbrains.annotations.Nullable;

/**
 * The four end-to-end checks of the chase that a pure unit test cannot see: a real Sable
 * sub-level assembly, the real {@code PhysicsBogeyBlockEntity}, the real rotating dimension's
 * worldgen and the real entity damage budget.
 *
 * <p><b>Their own world.</b> The suite only runs under {@code -PwithDragonChaseGameTest}, which
 * adds the {@code dragonChaseFixture} resource set: the rotating dimension in the {@code
 * minecraft:flat} preset and the {@code dimblend_dragonchase:empty} structure template. Neither
 * resource exists in a normal build, so the published jar cannot ship a test world.
 *
 * <p><b>Absolute sites, not template coordinates.</b> The template is empty and every structure is
 * placed at fixed corridor coordinates in {@code dimblend:rotating} — the same shape
 * {@code ChassisGameTests} uses. Each site owns its own X so two tests never fight over the same
 * blocks, and every test removes what it built before it succeeds.
 *
 * <p><b>Batches are per concern.</b> One batch per test keeps the director state machine
 * ({@link DragonChaseSavedData}) from being rewritten while another test inspects a build: the
 * director ticks the shared save every server tick, so a test that leaves {@code ACTIVE} state
 * behind would be read by the next batch in the same level.
 */
@PrefixGameTestTemplate(false)
public final class DragonChaseGameTests {
    /** The corridor every car is built in (the same key every other DimBlend feature resolves). */
    private static final ResourceKey<Level> ROTATING =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("dimblend:rotating"));

    /** Rails sit at this Y in the corridor; assemblies start one block above. */
    private static final int TRACK_Y = 64;

    /** The nest's fixed test site, far from every other site. */
    private static final int NEST_X = 512;
    /** The brake car and the crystal car, 64 blocks apart so their couplers cannot find each other. */
    private static final int BRAKE_X = 1024;
    private static final int CRYSTAL_X = 1088;
    /** The dragon / fireball site. */
    private static final int DRAGON_X = 1536;
    /** A random-drawn region (the only kind an override may target) that no test has generated. */
    private static final int BARREN_REGION = 90;

    public static void register(RegisterGameTestsEvent event) {
        event.register(DragonChaseGameTests.class);
    }

    private static ServerLevel rotating(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ROTATING);
        helper.assertTrue(level != null, "the rotating dimension must be loaded by the fixture preset");
        return level;
    }

    /**
     * Forces (or releases) every chunk a car or entity at {@code centerX} touches, pre-generates
     * the 2-chunk {@code ChunkStatus.FULL} ring {@code ChunkMap#prepareEntityTickingChunk} requires
     * before promoting a forced chunk to {@code FullChunkStatus.ENTITY_TICKING}, and binds the
     * generated track to Create's graph.
     *
     * <p>{@code setChunkForced} is required in {@code dimblend:rotating} because GameTestServer has
     * no players there: a plain {@code getChunk} has no ticket, so the next {@code chunkSource.tick}
     * unloads the chunk, unloading both vanilla entities ({@code UNLOADED_TO_CHUNK}) and
     * non-force-loaded Sable cars ({@code SubLevelHoldingChunkMap#processUnload}). Forcing world
     * chunks simulates a player's tracking window without adding a {@code SubLevelLoadingTicket} to
     * the car itself.
     */
    private static void forceSite(ServerLevel level, int centerX, boolean forced) {
        int minCoreX = (centerX - 16) >> 4;
        int maxCoreX = (centerX + 16) >> 4;
        if (forced) {
            for (int x = minCoreX - 2; x <= maxCoreX + 2; x++) {
                for (int z = -3; z <= 2; z++) {
                    level.getChunk(x, z);
                }
            }
        }
        for (int x = minCoreX; x <= maxCoreX; x++) {
            for (int z = -2; z <= 1; z++) {
                level.setChunkForced(x, z, forced);
            }
        }
        if (forced) {
            BlockPos rail = new BlockPos(centerX, TRACK_Y, 0);
            if (CorridorRails.isGeneratedTrack(level, rail)) {
                TrackPropagator.onRailAdded(level, rail, level.getBlockState(rail));
            }
        }
    }

    private static void whenEntityTicking(
            GameTestHelper helper, ServerLevel level, BlockPos a, BlockPos b, Runnable action) {
        if (level.isPositionEntityTicking(a) && level.isPositionEntityTicking(b)) {
            action.run();
            return;
        }
        helper.runAfterDelay(1, () -> whenEntityTicking(helper, level, a, b, action));
    }

    private static void preloadPlot(ServerLevel level, ServerSubLevel car) {
        var box = car.getPlot().getBoundingBox();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    @Nullable
    private static PhysicsBogeyBlockEntity bogeyOf(ServerLevel level, ServerSubLevel car, CarKind kind) {
        BlockPos pos = CarMotion.plotPos(car, CarPlans.of(kind).bogey());
        return level.getBlockEntity(pos) instanceof PhysicsBogeyBlockEntity bogey ? bogey : null;
    }

    private static String keyOf(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("<unregistered>");
    }

    // ------------------------------------------------------------------------------------------
    // 1. Director: nest assembly, kill rewards, WON phase survival (§3.3, §3.6)
    // ------------------------------------------------------------------------------------------

    /**
     * Assembles the real nest, kills a chase without a dragon and proves the nest survives the
     * post-kill lose check.
     *
     * <p>The regression this pins is P0-1: {@code Phase.WON} used to run the ACTIVE lose check, so
     * the rewarded nest was deleted on the first tick without a car behind it — exactly the moment
     * a victorious player slows down to open the shulker box (§3.6 says the failure rule no longer
     * applies after the kill).
     */
    @GameTest(template = "empty", templateNamespace = DimBlendDragonChase.MODID,
            batch = "dc_director", timeoutTicks = 200)
    public static void nestAssemblyRewardsAndWonPhaseSurvival(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        forceSite(level, NEST_X, true);
        helper.assertTrue(DragonCars.footprintReady(level, CarKind.NEST, NEST_X),
                "the nest footprint must be a full loaded chunk set before the build");

        ServerSubLevel nest = DragonCars.buildNest(level, NEST_X);
        helper.assertTrue(nest != null, "the nest assembles on generated corridor track");
        if (nest == null) {
            forceSite(level, NEST_X, false);
            return;
        }
        preloadPlot(level, nest);
        helper.assertTrue(DragonCarriages.kindOf(level, nest.getUniqueId()) == CarKind.NEST,
                "the assembled nest is registered as CarKind.NEST");
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        helper.assertTrue(container != null, "Sable must have a sub-level container in the corridor");
        if (container == null) {
            forceSite(level, NEST_X, false);
            return;
        }
        helper.assertTrue(container.collectForceLoadedSubLevels().stream()
                        .anyMatch(car -> car.getUniqueId().equals(nest.getUniqueId())),
                "the nest carries a force-load ticket for the whole chase");
        helper.assertTrue(NonCrewCarriages.ids(level).contains(nest.getUniqueId()),
                "the nest is reported as a non-crew carriage to every other DimBlend feature");

        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        data.phase = DragonChaseSavedData.Phase.ACTIVE;
        data.nestId = nest.getUniqueId();
        data.nestCenterX = NEST_X;
        data.generation = 1;
        data.graceUntil = 0L;

        ChaseDirector.killChase(level, data);
        helper.assertTrue(data.phase == DragonChaseSavedData.Phase.WON,
                "the kill moves the chase to WON, got " + data.phase);
        helper.assertTrue(data.generation == 2, "the kill bumps the generation once, got " + data.generation);
        ChaseDirector.killChase(level, data);
        helper.assertTrue(data.generation == 2,
                "a repeated kill cannot reward or bump twice, got " + data.generation);

        assertNestRewards(helper, level, nest);

        // Five real server ticks with graceAlreadyElapsed and no car behind the nest: every tick
        // used to end in failChase here, deleting the nest and its rewards.
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(data.phase == DragonChaseSavedData.Phase.WON,
                    "WON must survive the lose check with nothing behind the nest, got " + data.phase);
            helper.assertTrue(container.getSubLevel(nest.getUniqueId()) != null && !nest.isRemoved(),
                    "the rewarded nest must not be deleted after the kill (P0-1)");
            ChaseDirector.debugReset(level);
            forceSite(level, NEST_X, false);
            helper.succeed();
        });
    }

    /** The twenty ore cells and the shulker box, read from the nest's own plot. */
    private static void assertNestRewards(GameTestHelper helper, ServerLevel level, ServerSubLevel nest) {
        CarPlans.Plan plan = CarPlans.of(CarKind.NEST);
        helper.assertTrue(plan.oreCells().size() == 20,
                "the nest drawing has twenty ore cells, got " + plan.oreCells().size());
        Map<String, Integer> expected = Map.of(
                "minecraft:copper_block", 2,
                "minecraft:iron_block", 8,
                "create:zinc_block", 5,
                "minecraft:gold_block", 2,
                "minecraft:diamond_block", 2,
                "minecraft:netherite_block", 1);
        Map<String, Integer> counted = new HashMap<>();
        for (CarPlans.Cell cell : plan.oreCells()) {
            BlockPos pos = CarMotion.plotPos(nest, cell);
            BlockState state = level.getBlockState(pos);
            helper.assertTrue(!state.isAir(), "ore cell " + pos + " must hold its storage block");
            counted.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
        }
        helper.assertTrue(counted.equals(expected),
                "the twenty reward blocks are exactly the table's, got " + counted);

        CarPlans.Cell chest = plan.chestCell();
        helper.assertTrue(chest != null, "the nest drawing has a chest cell");
        if (chest == null) {
            return;
        }
        BlockPos chestPos = CarMotion.plotPos(nest, chest);
        helper.assertTrue(level.getBlockState(chestPos).is(CarBlocks.block(CarBlocks.SHULKER_BOX)),
                "the chest cell holds a shulker box");
        BlockEntity blockEntity = level.getBlockEntity(chestPos);
        helper.assertTrue(blockEntity instanceof RandomizableContainerBlockEntity,
                "the shulker box has a container block entity");
        if (!(blockEntity instanceof RandomizableContainerBlockEntity box)) {
            return;
        }
        helper.assertTrue(box.getContainerSize() == NestRewardTable.SHULKER_SLOTS,
                "a shulker box has " + NestRewardTable.SHULKER_SLOTS + " slots, got " + box.getContainerSize());
        int filled = 0;
        for (int slot = 0; slot < box.getContainerSize(); slot++) {
            if (!box.getItem(slot).isEmpty()) {
                filled++;
            }
        }
        helper.assertTrue(filled > 0, "the shulker box is stocked, filled slots " + filled);
    }

    // ------------------------------------------------------------------------------------------
    // 2. Cars: brake wiring, crystal spawning and the fall-away launch (§3.5)
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a brake car and a crystal car, checks their assembled wiring, then falls the brake car
     * away and proves the bogey is released before the 45° launch.
     */
    @GameTest(template = "empty", templateNamespace = DimBlendDragonChase.MODID,
            batch = "dc_cars", timeoutTicks = 200)
    public static void brakeAndCrystalCarsAssembleAndFallAway(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        forceSite(level, BRAKE_X, true);
        forceSite(level, CRYSTAL_X, true);

        // Let DistanceManager promote the forced corridor chunks to ENTITY_TICKING before spawning
        // crystals and stepping non-force-loaded sub-levels.
        helper.runAfterDelay(2, () -> {
            ServerSubLevel brake = DragonCars.buildBrakeCar(level, BRAKE_X, 0.0D);
            ServerSubLevel crystal = DragonCars.buildCrystalCar(level, CRYSTAL_X, 0.0D);
            helper.assertTrue(brake != null, "the brake car assembles");
            helper.assertTrue(crystal != null, "the crystal car assembles");
            if (brake == null || crystal == null) {
                forceSite(level, BRAKE_X, false);
                forceSite(level, CRYSTAL_X, false);
                return;
            }
            preloadPlot(level, brake);
            preloadPlot(level, crystal);
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            helper.assertTrue(container != null, "Sable must have a sub-level container in the corridor");
            if (container == null) {
                forceSite(level, BRAKE_X, false);
                forceSite(level, CRYSTAL_X, false);
                return;
            }
            helper.assertTrue(container.collectForceLoadedSubLevels().stream()
                            .noneMatch(car -> car.getUniqueId().equals(brake.getUniqueId())),
                    "the brake car is deliberately not force-loaded (§3.5)");
            helper.assertTrue(container.collectForceLoadedSubLevels().stream()
                            .noneMatch(car -> car.getUniqueId().equals(crystal.getUniqueId())),
                    "the crystal car is deliberately not force-loaded (§3.5)");

            CarPlans.Plan brakePlan = CarPlans.of(CarKind.BRAKE);
            BlockPos lampPos = CarMotion.plotPos(brake, brakePlan.lamp());
            helper.assertTrue(level.getBlockState(lampPos).is(CarBlocks.block(CarBlocks.REDSTONE_LAMP)),
                    "the brake car's lamp cell holds a redstone lamp");
            helper.assertTrue(level.getBlockState(lampPos).getValue(BlockStateProperties.LIT),
                    "the primed redstone signal must light the lamp");
            PhysicsBogeyBlockEntity bogey = bogeyOf(level, brake, CarKind.BRAKE);
            helper.assertTrue(bogey != null, "the brake car has a physics bogey");
            if (bogey == null) {
                forceSite(level, BRAKE_X, false);
                forceSite(level, CRYSTAL_X, false);
                return;
            }
            helper.assertTrue(bogey.getOptions().enabled, "the assembled bogey starts enabled");
            helper.assertTrue(bogey.getOptions().controlMode == PhysicsBogeyControlMode.BRAKING,
                    "the bogey brakes from the redstone signal it reads, mode " + bogey.getOptions().controlMode);

            List<EndCrystal> crystals = DragonCarriages.aliveCrystals(level);
            helper.assertTrue(!crystals.isEmpty() && crystals.size() <= 4,
                    "the crystal car grows 1-4 end crystals, got " + crystals.size());
            helper.assertTrue(DragonCarriages.healingCrystalCount(level) >= 1,
                    "the live crystals are counted for the dragon's unlimited-range heal, got "
                            + DragonCarriages.healingCrystalCount(level));

            DragonCarriages.fallAway(level, brake);
            PhysicsBogeyBlockEntity falling = bogeyOf(level, brake, CarKind.BRAKE);
            helper.assertTrue(falling != null && !falling.getOptions().enabled,
                    "fall-away disables the bogey at once, so the rail lock can release before the launch");

            helper.runAfterDelay(CarRules.FALL_ARM_TICKS + 2, () -> {
                RigidBodyHandle handle = RigidBodyHandle.of(brake);
                helper.assertTrue(handle != null && handle.isValid(), "the launched car still has a body");
                if (handle != null) {
                    helper.assertTrue(handle.getLinearVelocity(new Vector3d()).y > 0.5D,
                            "fall-away launches the car upward, vy " + handle.getLinearVelocity(new Vector3d()).y);
                }
                DragonCarriages.removeNow(level, brake);
                DragonCarriages.removeNow(level, crystal);
                forceSite(level, BRAKE_X, false);
                forceSite(level, CRYSTAL_X, false);
                helper.runAfterDelay(5, helper::succeed);
            });
        });
    }

    // ------------------------------------------------------------------------------------------
    // 3. Worldgen: the forced END_BARREN region (§3.3, §4)
    // ------------------------------------------------------------------------------------------

    /**
     * Forces a random region to barren End and proves the whole pipeline agrees: the layout reports
     * the End lane, the biome source answers {@code end_barrens}, and the generated chunk carries
     * that single biome and no structure starts.
     */
    @GameTest(template = "empty", templateNamespace = DimBlendDragonChase.MODID,
            batch = "dc_worldgen", timeoutTicks = 200)
    public static void barrenEndOverrideBiomeAndStructures(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        int bandSize = RegionLanes.bandSize(level);
        helper.assertTrue(bandSize > 0, "the corridor reports its band size");
        if (bandSize <= 0) {
            return;
        }
        int blockX = BARREN_REGION * bandSize + 256;
        helper.assertTrue(RegionOverrides.forceLane(level.getServer(), BARREN_REGION,
                        RegionOverrides.Override.END_BARREN),
                "a random-drawn region accepts the END_BARREN override");
        helper.assertTrue("end".equals(RegionLanes.laneName(level, BARREN_REGION)),
                "the forced region reports the end lane, got " + RegionLanes.laneName(level, BARREN_REGION));

        Holder<Biome> noiseBiome = level.getChunkSource().getGenerator().getBiomeSource()
                .getNoiseBiome(blockX >> 2, 16, 0, level.getChunkSource().randomState().sampler());
        helper.assertTrue(noiseBiome.is(Biomes.END_BARRENS),
                "the barren region's biome source answers end_barrens at quart X " + (blockX >> 2)
                        + ", got " + keyOf(noiseBiome));

        var chunk = level.getChunk(blockX >> 4, 0);
        helper.assertTrue(chunk.getAllStarts().isEmpty(),
                "a barren End chunk carries no structure starts, got " + chunk.getAllStarts().keySet());
        helper.assertTrue(level.getBiome(new BlockPos(blockX, 64, 0)).is(Biomes.END_BARRENS),
                "the generated chunk's palette is the single barren biome, got "
                        + keyOf(level.getBiome(new BlockPos(blockX, 64, 0))));

        // §4 corridor contract in a forced barren region: the End lane's profile is the phantom
        // wide gauge with no foundation row (CorridorTrackProfile.END: material cobblestone,
        // width 0). Track Y=64, roadbed Y=63.
        BlockState trackState = level.getBlockState(new BlockPos(blockX, TRACK_Y, 0));
        helper.assertTrue(trackState.getBlock() instanceof ITrackBlock,
                "the barren End corridor carries a rail, got "
                        + BuiltInRegistries.BLOCK.getKey(trackState.getBlock()));
        ResourceLocation phantomWide =
                ResourceLocation.fromNamespaceAndPath("railways", "track_phantom_wide");
        if (BuiltInRegistries.BLOCK.containsKey(phantomWide)) {
            helper.assertTrue(BuiltInRegistries.BLOCK.getKey(trackState.getBlock()).equals(phantomWide),
                    "the End lane's rail is the phantom wide gauge, got "
                            + BuiltInRegistries.BLOCK.getKey(trackState.getBlock()));
        }
        // The cell under the track is the roadbed row the End lane never writes: no lane material
        // may show up there. Vanilla End islands still generate at this X (the forced region keeps
        // the End generator), so unchanged terrain is either the island's own end_stone or air —
        // a foundation row would be cobblestone, which is exactly what this rejects.
        BlockState underTrack = level.getBlockState(new BlockPos(blockX, TRACK_Y - 1, 0));
        helper.assertFalse(isRoadbedMaterial(underTrack),
                "the End lane writes no foundation under its track, got "
                        + BuiltInRegistries.BLOCK.getKey(underTrack.getBlock()));
        helper.assertTrue(underTrack.isAir() || underTrack.is(Blocks.END_STONE),
                "the cell under the End track is untouched End island or air, got "
                        + BuiltInRegistries.BLOCK.getKey(underTrack.getBlock()));
        helper.succeed();
    }

    /** Roadbed materials of the corridor lanes' profiles; the End lane has none of them. */
    private static boolean isRoadbedMaterial(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.POLISHED_DEEPSLATE);
    }

    // ------------------------------------------------------------------------------------------
    // 4. Entities: damage budget, GENERIC_KILL and the fireball's saved state (§3.4, §3.6)
    // ------------------------------------------------------------------------------------------

    /**
     * Spawns a real chase dragon, proves the 2%/s damage cap, proves {@code GENERIC_KILL} bypasses
     * it, and round-trips a fireball through NBT so a shot survives a chunk save.
     */
    @GameTest(template = "empty", templateNamespace = DimBlendDragonChase.MODID,
            batch = "dc_entities", timeoutTicks = 200)
    public static void dragonDamageCapKillBypassAndFireballNbt(GameTestHelper helper) {
        ServerLevel level = rotating(helper);
        forceSite(level, DRAGON_X, true);

        Vec3 from = new Vec3(DRAGON_X, 96.0D, 0.0D);
        Vec3 target = new Vec3(DRAGON_X - 12.0D, 65.0D, 0.0D);

        // Wait for ChunkMap#prepareEntityTickingChunk + PersistentEntitySectionManager to promote
        // both endpoints to FullChunkStatus.ENTITY_TICKING / Visibility.TICKING before adding
        // entities in dimblend:rotating.
        whenEntityTicking(helper, level, BlockPos.containing(from), BlockPos.containing(target), () -> {
            ChaseDragon dragon = ChaseDragon.createChase(level, from, 400.0F);
            helper.assertTrue(level.addFreshEntity(dragon), "the chase dragon spawns in the corridor");
            helper.assertTrue(ChaseDragon.FOLLOW_SWAY_AMPLITUDE == 32.0D,
                    "the chase sway stays the requirement's ±32, got " + ChaseDragon.FOLLOW_SWAY_AMPLITUDE);
            helper.assertTrue(ChaseDragon.FOLLOW_BOB_AMPLITUDE == 16.0D,
                    "the chase bob is the requirement's ±16, got " + ChaseDragon.FOLLOW_BOB_AMPLITUDE);

            helper.assertTrue(dragon.hurt(level.damageSources().magic(), 100.0F), "the hit lands");
            helper.assertTrue(dragon.getHealth() == 392.0F,
                    "2% of 400 health per second caps the 100-point hit at 8, health " + dragon.getHealth());

            ChaseFireball shot = new ChaseFireball(DragonChaseRegistries.DRAGON_FIREBALL.get(), level);
            shot.launch(from, target, ChaseFireball.TRAVEL_TICKS);
            CompoundTag tag = new CompoundTag();
            shot.addAdditionalSaveData(tag);
            ChaseFireball reloaded = new ChaseFireball(DragonChaseRegistries.DRAGON_FIREBALL.get(), level);
            reloaded.readAdditionalSaveData(tag);
            // A real reload restores the position from the entity's own NBT (written by the chunk); this
            // tag carries only the scripted trajectory, so the position is supplied here the same way.
            reloaded.setPos(from.x, from.y, from.z);
            helper.assertTrue(level.addFreshEntity(reloaded), "the reloaded shot is accepted by the level");

            helper.runAfterDelay(3, () -> {
                helper.assertTrue(!reloaded.isRemoved(),
                        "a three-tick-old shot is still in flight (it only discards on impact)");
                helper.assertTrue(reloaded.position().distanceTo(target) < from.distanceTo(target),
                        "the reloaded shot travels along the lerped path toward its target, at "
                                + reloaded.position());
                helper.assertTrue(reloaded.position().distanceTo(from) > 1.0D,
                        "the reloaded shot left its launch point, at " + reloaded.position());

                helper.assertTrue(dragon.hurt(level.damageSources().genericKill(), Float.MAX_VALUE),
                        "the kill hit lands");
                helper.assertTrue(dragon.mode() == ChaseDragonHandle.Mode.DYING,
                        "GENERIC_KILL bypasses the 2%/s cap, mode " + dragon.mode());
                dragon.discardSilently();
                reloaded.discard();
                forceSite(level, DRAGON_X, false);
                helper.succeed();
            });
        });
    }

    private DragonChaseGameTests() {
    }
}

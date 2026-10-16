package dimblend.experience.gametest;

import com.adonis.fluid.block.CopperSink.CopperSinkBlockEntity;
import com.adonis.fluid.block.CopperTap.CopperTapBlock;
import com.adonis.fluid.block.CopperTap.CopperTapBlockEntity;
import com.jesz.createdieselgenerators.CDGConfig;
import com.jesz.createdieselgenerators.CDGBlocks;
import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.hlysine.create_connected.content.kineticbattery.KineticBatteryBlockEntity;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllFluids;
import com.simibubi.create.content.kinetics.mixer.MixingRecipe;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.item.ModItems;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused runtime tests. Explicitly registered only with CDG, Fluid and YAT present. */
@PrefixGameTestTemplate(false)
public final class BatchWaterCompatGameTests {
    private static final String NS = "dimblend_compat_batch";
    private static final BlockPos MID = new BlockPos(1, 1, 1);

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void dieselRedstoneAndFuelChecks(GameTestHelper helper) {
        boolean behavior = Config.DIESEL_ENGINE_BEHAVIOR.get();
        boolean analog = CDGConfig.ANALOG_SPEED_CONTROL.get();
        boolean redstone = CDGConfig.ENGINES_DISABLED_WITH_REDSTONE.get();
        try {
            Config.DIESEL_ENGINE_BEHAVIOR.set(true);
            CDGConfig.ANALOG_SPEED_CONTROL.set(false);
            CDGConfig.ENGINES_DISABLED_WITH_REDSTONE.set(true);
            for (var block : java.util.List.of(CDGBlocks.DIESEL_ENGINE.get(), CDGBlocks.MODULAR_DIESEL_ENGINE.get(),
                    CDGBlocks.HUGE_DIESEL_ENGINE.get())) {
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                helper.setBlock(MID, block.defaultBlockState().setValue(BlockStateProperties.POWERED, true));
                IEngine engine = (IEngine) helper.getBlockEntity(MID);
                helper.assertTrue(!engine.enabled(), id + ": empty fuel must still stop");
                engine.getTank().fill(new FluidStack(BuiltInRegistries.FLUID.get(
                        ResourceLocation.parse("createdieselgenerators:diesel")), 100), FluidAction.EXECUTE);
                helper.assertTrue(engine.validFS(), id + ": test diesel must be valid fuel");
                helper.assertTrue(engine.enabled(), id + ": redstone must not stop a fueled engine");
                if (engine instanceof DieselEngineBlockEntity normal) normal.setAnalogSignal(15);
                if (engine instanceof ModularDieselEngineBlockEntity modular) modular.setAnalogSignal(15);
                if (engine instanceof HugeDieselEngineBlockEntity huge) huge.setAnalogSignal(15);
                CDGConfig.ANALOG_SPEED_CONTROL.set(true);
                helper.assertTrue(engine.getThrottle() == 1.0F, id + ": throttle must stay full");
                Config.DIESEL_ENGINE_BEHAVIOR.set(false);
                helper.assertTrue(engine.getThrottle() == 0.0F, id + ": disabling must restore analog signal 15 to zero throttle");
                CDGConfig.ANALOG_SPEED_CONTROL.set(false);
                helper.assertTrue(!engine.enabled(), id + ": disabling the feature must restore native redstone stop");
                Config.DIESEL_ENGINE_BEHAVIOR.set(true);
            }
        } finally {
            Config.DIESEL_ENGINE_BEHAVIOR.set(behavior);
            CDGConfig.ANALOG_SPEED_CONTROL.set(analog);
            CDGConfig.ENGINES_DISABLED_WITH_REDSTONE.set(redstone);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void finiteSinkKeepsAmountAndPurityAcrossToggle(GameTestHelper helper) {
        boolean previous = Config.COPPER_SINK_INFINITE.get();
        try {
            Config.COPPER_SINK_INFINITE.set(false);
            helper.setBlock(MID, block("fluid:copper_sink").defaultBlockState());
            var sink = (CopperSinkBlockEntity) helper.getBlockEntity(MID);
            var tank = sink.getTank();
            helper.assertTrue(tank.getFluidAmount() == 0, "a new finite sink must be empty");
            tank.fill(water(3, 1500), FluidAction.EXECUTE);
            var drained = tank.drain(500, FluidAction.EXECUTE);
            helper.assertTrue(drained.getAmount() == 500 && FluidPurityHelper.getPurity(drained) == 3,
                    "finite sink must preserve extracted purity");
            helper.assertTrue(tank.getFluidAmount() == 1000, "finite extraction must consume water");
            Config.COPPER_SINK_INFINITE.set(true);
            helper.assertTrue(tank.drain(5000, FluidAction.SIMULATE).getAmount() == 5000, "infinity toggle must apply to drain");
            Config.COPPER_SINK_INFINITE.set(false);
            helper.assertTrue(tank.getFluidAmount() == 1000 && FluidPurityHelper.getPurity(tank.getFluid()) == 3,
                    "switching off infinity must retain the underlying stored water");
            tank.fill(water(0, 500), FluidAction.EXECUTE);
            helper.assertTrue(tank.getFluidAmount() == 1500 && FluidPurityHelper.getPurity(tank.getFluid()) == 0,
                    "mixed purity must follow YAT's lower-purity rule");
            helper.assertTrue(sink.getFillState() == 0.75F, "finite display must use the actual fill ratio");
        } finally {
            Config.COPPER_SINK_INFINITE.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void tapLeaksOnlyAfterGraceAndResetsWhenClosed(GameTestHelper helper) {
        boolean previous = Config.COPPER_SINK_INFINITE.get();
        try {
            Config.COPPER_SINK_INFINITE.set(false);
            helper.setBlock(MID, block("fluid:copper_sink").defaultBlockState());
            var source = ((CopperSinkBlockEntity) helper.getBlockEntity(MID)).getTank();
            source.fill(water(0, 1000), FluidAction.EXECUTE);
            BlockPos tapPos = MID.south();
            helper.setBlock(tapPos, block("fluid:copper_tap").defaultBlockState()
                    .setValue(CopperTapBlock.FACING, Direction.SOUTH).setValue(BlockStateProperties.OPEN, true));
            var tap = (CopperTapBlockEntity) helper.getBlockEntity(tapPos);
            for (int i = 0; i < 299; i++) tap.tick();
            helper.assertTrue(source.getFluidAmount() == 1000, "no water may leak before 15 seconds");
            tap.tick();
            helper.assertTrue(source.getFluidAmount() == 999, "first 1mB leak must occur at 15 seconds");
            for (int i = 0; i < 20; i++) tap.tick();
            helper.assertTrue(source.getFluidAmount() == 998, "subsequent leakage must be exactly 1mB per second");
            helper.setBlock(tapPos, tap.getBlockState().setValue(BlockStateProperties.OPEN, false));
            tap.tick();
            helper.setBlock(tapPos, tap.getBlockState().setValue(BlockStateProperties.OPEN, true));
            for (int i = 0; i < 299; i++) tap.tick();
            helper.assertTrue(source.getFluidAmount() == 998, "closing must reset the grace period");
        } finally {
            Config.COPPER_SINK_INFINITE.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void waterRecipesUseExactPurityAndKeepCatalyst(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager();
        helper.assertTrue(manager.byKey(ResourceLocation.parse("yet_another_thirst:clean_fabric_filter")).isEmpty(),
                "clogged fabric cleaning recipe must be removed");
        helper.assertTrue(manager.byKey(ResourceLocation.parse("yet_another_thirst:water_bottle_from_smelting_acceptable_purity_0")).isEmpty(),
                "old purification shortcut must be removed");
        var recipe = (MixingRecipe) manager.byKey(ResourceLocation.parse("dimblend_craft:acceptable_water_from_mixing")).orElseThrow().value();
        var input = recipe.getFluidIngredients().getFirst();
        helper.assertTrue(input.amount() == 1000 && input.ingredient().test(water(1, 1000))
                        && !input.ingredient().test(water(0, 1000)) && !input.ingredient().test(water(2, 1000)),
                "mixing purity predicate: " + input.ingredient().getClass().getSimpleName() + ", amount=" + input.amount()
                        + ", matches=" + input.ingredient().test(water(0, 1000)) + "/" + input.ingredient().test(water(1, 1000))
                        + "/" + input.ingredient().test(water(2, 1000)));
        helper.assertTrue(recipe.getFluidResults().getFirst().getAmount() == 500
                        && FluidPurityHelper.getPurity(recipe.getFluidResults().getFirst()) == 2,
                "mixing must produce 500mB acceptable water");
        helper.assertTrue(recipe.getIngredients().getFirst().test(new ItemStack(Items.BLAZE_POWDER))
                        && recipe.getRollableResults().getFirst().getStack().is(Items.BLAZE_POWDER),
                "blaze powder must be required and returned");
        var smoking = manager.byKey(ResourceLocation.parse("dimblend_craft:dirty_water_bottle_from_smoking")).orElseThrow().value();
        var cooking = (net.minecraft.world.item.crafting.SmokingRecipe) smoking;
        helper.assertTrue(cooking.matches(new SingleRecipeInput(WaterPurity.addPurity(WaterPurity.waterPotion(), 0)), helper.getLevel()),
                "dirty water bottle must match smoking");
        helper.assertTrue(!cooking.matches(new SingleRecipeInput(new ItemStack(ModItems.TERRACOTTA_WATER_BOWL.get())), helper.getLevel()),
                "bowls must not participate in purification");
        helper.succeed();
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void sinkRightClickPreservesAllBottlePurities(GameTestHelper helper) {
        boolean previous = Config.COPPER_SINK_INFINITE.get();
        try {
            Config.COPPER_SINK_INFINITE.set(false);
            helper.setBlock(MID, block("fluid:copper_sink").defaultBlockState());
            var sink = (CopperSinkBlockEntity) helper.getBlockEntity(MID);
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            var absolute = helper.absolutePos(MID);
            var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
            for (int purity = 0; purity <= 3; purity++) {
                player.setItemInHand(InteractionHand.MAIN_HAND, WaterPurity.addPurity(WaterPurity.waterPotion(), purity));
                sink.getBlockState().useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
                helper.assertTrue(sink.getTank().getFluidAmount() == 250
                                && FluidPurityHelper.getPurity(sink.getTank().getFluid()) == purity,
                        "right-click pour must preserve purity " + purity);
                helper.assertTrue(player.getMainHandItem().is(Items.GLASS_BOTTLE), "pour must return the empty bottle");
                sink.getBlockState().useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
                helper.assertTrue(sink.getTank().isEmpty() && WaterPurity.getPurity(player.getMainHandItem()) == purity,
                        "right-click extraction must preserve purity " + purity);
            }
            sink.getTank().fill(new FluidStack(Fluids.WATER, 250), FluidAction.EXECUTE);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
            sink.getBlockState().useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(WaterPurity.getPurity(player.getMainHandItem()) == 0,
                    "unmarked sink water must produce dirty water bottles");
        } finally {
            Config.COPPER_SINK_INFINITE.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 40)
    public static void sinkTeaExtractionKeepsTheNativePath(GameTestHelper helper) {
        boolean previous = Config.COPPER_SINK_INFINITE.get();
        try {
            Config.COPPER_SINK_INFINITE.set(false);
            helper.setBlock(MID, block("fluid:copper_sink").defaultBlockState());
            var sink = (CopperSinkBlockEntity) helper.getBlockEntity(MID);
            sink.getTank().fill(new FluidStack(AllFluids.TEA.get(), 250), FluidAction.EXECUTE);
            var request = new FluidStack(AllFluids.TEA.get(), 100);
            helper.assertTrue(sink.getTank().drain(request, FluidAction.SIMULATE).getAmount() == 100
                            && sink.getTank().getFluidAmount() == 250,
                    "resource drain simulation must preserve the stored amount");
            helper.assertTrue(sink.getTank().drain(new FluidStack(Fluids.WATER, 100), FluidAction.EXECUTE).isEmpty(),
                    "resource drain must reject a different fluid");
            var differentComponents = request.copy();
            differentComponents.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("different components"));
            helper.assertTrue(sink.getTank().drain(differentComponents, FluidAction.EXECUTE).isEmpty()
                            && sink.getTank().getFluidAmount() == 250,
                    "resource drain must reject mismatched components without consuming fluid");
            helper.assertTrue(sink.getTank().drain(request, FluidAction.EXECUTE).getAmount() == 100
                            && sink.getTank().getFluidAmount() == 150,
                    "resource drain execution must consume exactly the requested amount");
            sink.getTank().fill(new FluidStack(AllFluids.TEA.get(), 100), FluidAction.EXECUTE);
            var player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
            var absolute = helper.absolutePos(MID);
            sink.getBlockState().useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
            helper.assertTrue(player.getInventory().countItem(AllItems.BUILDERS_TEA.get()) == 1,
                    "non-water extraction must still use Create's native tea bottling");
            helper.assertTrue(sink.getTank().isEmpty(), "bottling tea must consume the stored 250mB");
            player.getInventory().selected = 1;
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GLASS_BOTTLE));
            sink.getBlockState().useItemOn(player.getMainHandItem(), helper.getLevel(), player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
            helper.assertTrue(player.getInventory().countItem(AllItems.BUILDERS_TEA.get()) == 1
                            && player.getMainHandItem().is(Items.GLASS_BOTTLE),
                    "an empty sink must not produce another tea bottle");
        } finally {
            Config.COPPER_SINK_INFINITE.set(previous);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 60)
    public static void kineticBatteryConflictDoesNotRestoreDestroyedBlock(GameTestHelper helper) {
        var batteryBlock = block("create_connected:kinetic_battery");
        helper.setBlock(MID, batteryBlock.defaultBlockState());
        var battery = (KineticBatteryBlockEntity) helper.getBlockEntity(MID);
        battery.setBatteryLevel(KineticBatteryBlockEntity.getMaxBatteryLevel());
        helper.setBlock(MID.north(), com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        var motor = (com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) helper.getBlockEntity(MID.north());
        motor.generatedSpeed.setValue(-32);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(battery.getSpeed() < 0 && battery.hasSource(), "precondition: battery must follow the negative external network");
            helper.setBlock(MID.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(helper.getBlockState(MID).isAir(),
                        "opposite-direction discharge must destroy the battery permanently, got " + helper.getBlockState(MID)
                                + ", oldBE removed=" + battery.isRemoved() + ", speed=" + battery.getSpeed()
                                + ", hasNetwork=" + battery.hasNetwork() + ", charge=" + battery.getBatteryLevel());
                var drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(helper.absolutePos(MID)).inflate(2));
                long count = drops.stream().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                        .filter(stack -> stack.is(batteryBlock.asItem()) || BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString().equals("create_connected:charged_kinetic_battery"))
                        .mapToLong(ItemStack::getCount).sum();
                helper.assertTrue(count == 1, "conflict must drop exactly one battery, got " + count);
                helper.succeed();
            });
        });
    }

    private static FluidStack water(int purity, int amount) {
        return FluidPurityHelper.addPurity(new FluidStack(Fluids.WATER, amount), purity);
    }

    @GameTest(template = "empty", templateNamespace = NS, timeoutTicks = 100)
    public static void kineticBatteryConflictOnSableDoesNotRestoreDestroyedBlock(GameTestHelper helper) {
        var level = helper.getLevel();
        var batteryBlock = block("create_connected:kinetic_battery");
        helper.setBlock(MID, batteryBlock.defaultBlockState());
        ((KineticBatteryBlockEntity) helper.getBlockEntity(MID)).setBatteryLevel(KineticBatteryBlockEntity.getMaxBatteryLevel());
        helper.setBlock(MID.north(), com.simibubi.create.AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        ((com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity) helper.getBlockEntity(MID.north()))
                .generatedSpeed.setValue(-32);
        helper.setBlock(MID.east(), net.minecraft.world.level.block.Blocks.STONE);
        var world = helper.absolutePos(MID);
        var subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                java.util.List.of(world, world.north(), world.east()), new BoundingBox3i(world.north(), world.east()));
        helper.assertTrue(subLevel != null, "battery fixture must assemble into Sable");
        var plot = BlockPos.containing(subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(level.getBlockEntity(plot) instanceof KineticBatteryBlockEntity,
                    "battery must be loaded in its Sable plot");
            var battery = (KineticBatteryBlockEntity) level.getBlockEntity(plot);
            helper.assertTrue(battery.getSpeed() < 0 && battery.hasSource(), "Sable battery must follow the negative external network");
            level.setBlockAndUpdate(plot.east(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(level.getBlockState(plot).isAir(), "Sable conflict must not restore the destroyed battery");
                var center = subLevel.logicalPose().transformPosition(Vec3.atCenterOf(plot));
                var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(center, center).inflate(3));
                drops.addAll(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(plot).inflate(2)));
                // Sable may project a plot query onto the same world entities; count each entity once.
                long count = drops.stream().distinct().map(net.minecraft.world.entity.item.ItemEntity::getItem)
                        .filter(stack -> stack.is(batteryBlock.asItem()) || BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString().equals("create_connected:charged_kinetic_battery"))
                        .mapToLong(ItemStack::getCount).sum();
                helper.assertTrue(count == 1, "Sable conflict must drop exactly one battery, got " + count
                        + ", entities=" + drops.stream().map(entity -> entity.getUUID() + ":" + entity.position() + ":" + entity.getItem()).toList());
                helper.succeed();
            });
        });
    }

    private BatchWaterCompatGameTests() { }
}

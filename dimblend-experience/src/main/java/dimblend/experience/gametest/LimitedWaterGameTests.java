package dimblend.experience.gametest;

import java.util.ArrayList;
import java.util.List;

import dimblend.experience.Config;
import dimblend.experience.exploration.IcePlacementRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G3 有限水与冰放置禁令的运行时织入检查（{@code Level#setBlock} 改写与
 * {@code BlockItem#place} 拦截都是 mixin 行为，纯函数单测覆盖不到）。
 *
 * <p>全部用例体同步执行（无 runAfterDelay）：开头把维度门临时指向主世界（GameTest 只能
 * 跑在主世界）并打开开关，finally 还原配置、让测试玩家退场、清理方块——单线程顺序调用下
 * 无并发互踩窗口。玩家位置按「相对源水格中心的偏移」给出，覆盖 8 格球半径边界。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class LimitedWaterGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final String BATCH = "limited_water";
    private static final BlockPos WATER_REL = new BlockPos(1, 2, 1);
    private static final BlockPos STAND_REL = new BlockPos(1, 1, 1);
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState SOURCE = Blocks.WATER.defaultBlockState();
    private static final BlockState FLOWING_7 = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);

    private record SavedConfig(String rotatingId, boolean limitedWater, boolean iceBan) {
    }

    private record Nearby(GameType mode, Vec3 offset) {
    }

    private static Nearby nearby(GameType mode, double dx, double dy, double dz) {
        return new Nearby(mode, new Vec3(dx, dy, dz));
    }

    private static SavedConfig enableRules() {
        SavedConfig saved = new SavedConfig(Config.ROTATING_DIMENSION_ID.get(),
                Config.LIMITED_WATER.get(), Config.ICE_PLACEMENT_BAN.get());
        Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
        Config.LIMITED_WATER.set(true);
        Config.ICE_PLACEMENT_BAN.set(true);
        return saved;
    }

    private static void restore(SavedConfig saved) {
        Config.ROTATING_DIMENSION_ID.set(saved.rotatingId());
        Config.LIMITED_WATER.set(saved.limitedWater());
        Config.ICE_PLACEMENT_BAN.set(saved.iceBan());
    }

    /**
     * 预置四邻（不足 4 个补石头；预置期间关闭开关，免得预置的源水自身被改写），
     * 放入玩家后按当前开关写一次源水，返回写入后的方块态。
     */
    private static BlockState writeSource(GameTestHelper helper, List<BlockState> neighbours, Nearby... players) {
        ServerLevel level = helper.getLevel();
        BlockPos water = helper.absolutePos(WATER_REL);
        List<ServerPlayer> spawned = new ArrayList<>();
        boolean enabled = Config.LIMITED_WATER.get();
        List<Direction> sides = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
        try {
            Config.LIMITED_WATER.set(false);
            for (int i = 0; i < sides.size(); i++) {
                BlockState state = i < neighbours.size() ? neighbours.get(i) : STONE;
                level.setBlock(water.relative(sides.get(i)), state, Block.UPDATE_CLIENTS);
            }
            level.setBlock(water, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (Nearby player : players) {
                spawned.add(GameTestPlayers.spawn(helper, player.mode(), water.getCenter().add(player.offset())));
            }
            Config.LIMITED_WATER.set(enabled);
            level.setBlock(water, SOURCE, Block.UPDATE_ALL);
            return level.getBlockState(water);
        } finally {
            Config.LIMITED_WATER.set(enabled);
            GameTestPlayers.removeAll(helper, spawned);
            level.setBlock(water, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (Direction side : sides) {
                level.setBlock(water.relative(side), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static void assertState(GameTestHelper helper, BlockState expected, BlockState actual, String why) {
        helper.assertTrue(actual == expected, why + " (expected " + expected + ", got " + actual + ")");
    }

    // —— 四邻计数 ——

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void survivalNearbyIsolatedSourceBecomesFlowing(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            assertState(helper, FLOWING_7, writeSource(helper, List.of(), nearby(GameType.SURVIVAL, 3, 0, 0)),
                    "0 neighbours + survival nearby must downgrade");
            assertState(helper, FLOWING_7, writeSource(helper, List.of(SOURCE), nearby(GameType.SURVIVAL, 3, 0, 0)),
                    "1 source neighbour + survival nearby must downgrade");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void twoWaterOrIceNeighboursKeepSource(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            Nearby survival = nearby(GameType.SURVIVAL, 3, 0, 0);
            assertState(helper, SOURCE, writeSource(helper, List.of(SOURCE, SOURCE), survival),
                    "2 source neighbours keep the source");
            assertState(helper, SOURCE, writeSource(helper, List.of(Blocks.PACKED_ICE.defaultBlockState(),
                    Blocks.BLUE_ICE.defaultBlockState()), survival), "packed ice + blue ice count as 2");
            assertState(helper, SOURCE, writeSource(helper, List.of(SOURCE, Blocks.ICE.defaultBlockState()), survival),
                    "source + ice count as 2");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void flowingWaterloggedAndFrostedIceDoNotCount(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            BlockState waterloggedSlab = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.WATERLOGGED, true);
            assertState(helper, FLOWING_7, writeSource(helper,
                    List.of(FLOWING_7, waterloggedSlab, Blocks.FROSTED_ICE.defaultBlockState(), FLOWING_7),
                    nearby(GameType.SURVIVAL, 3, 0, 0)),
                    "flowing water, waterlogged blocks and frosted ice must not count");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    // —— 决定者：8 格内最近的非旁观玩家 ——

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void creativeNearestSkipsCheck(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            assertState(helper, SOURCE, writeSource(helper, List.of(), nearby(GameType.CREATIVE, 3, 0, 0)),
                    "creative nearest player: no check");
            assertState(helper, SOURCE, writeSource(helper, List.of(), nearby(GameType.CREATIVE, 8, 0, 0)),
                    "creative exactly 8 blocks away is still within the radius");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void noDeciderWithinEightIsChecked(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            assertState(helper, FLOWING_7, writeSource(helper, List.of()),
                    "no player at all: treated as survival");
            assertState(helper, FLOWING_7, writeSource(helper, List.of(), nearby(GameType.CREATIVE, 6, 6, 0)),
                    "creative 8.49 blocks away (3D) is outside the radius: checked");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void nearestPlayerDecides(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            assertState(helper, FLOWING_7, writeSource(helper, List.of(),
                    nearby(GameType.SURVIVAL, 2, 0, 0), nearby(GameType.CREATIVE, 0, 0, 5)),
                    "survival nearer than creative: checked");
            assertState(helper, SOURCE, writeSource(helper, List.of(),
                    nearby(GameType.CREATIVE, 2, 0, 0), nearby(GameType.SURVIVAL, 0, 0, 5)),
                    "creative nearer than survival: no check");
            assertState(helper, FLOWING_7, writeSource(helper, List.of(), nearby(GameType.ADVENTURE, 2, 0, 0)),
                    "adventure nearest: checked");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void spectatorsAreSkipped(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            assertState(helper, SOURCE, writeSource(helper, List.of(),
                    nearby(GameType.SPECTATOR, 1, 0, 0), nearby(GameType.CREATIVE, 0, 0, 5)),
                    "spectator skipped, creative behind it decides: no check");
            assertState(helper, FLOWING_7, writeSource(helper, List.of(), nearby(GameType.SPECTATOR, 1, 0, 0)),
                    "only a spectator nearby counts as nobody: checked");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void switchOffKeepsSource(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            Config.LIMITED_WATER.set(false);
            assertState(helper, SOURCE, writeSource(helper, List.of(), nearby(GameType.SURVIVAL, 3, 0, 0)),
                    "limitedWater=false leaves writes untouched");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void bucketPourGoesThroughTheSameCheck(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        BlockPos water = helper.absolutePos(WATER_REL);
        List<ServerPlayer> spawned = new ArrayList<>();
        try {
            BucketItem bucket = (BucketItem) Items.WATER_BUCKET;
            ServerPlayer survival = GameTestPlayers.spawn(helper, GameType.SURVIVAL, water.getCenter().add(2, 0, 0));
            spawned.add(survival);
            bucket.emptyContents(survival, level, water, null, new ItemStack(Items.WATER_BUCKET));
            assertState(helper, FLOWING_7, level.getBlockState(water), "survival bucket pour, isolated: downgraded");
            level.setBlock(water, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            GameTestPlayers.removeAll(helper, spawned);

            ServerPlayer creative = GameTestPlayers.spawn(helper, GameType.CREATIVE, water.getCenter().add(2, 0, 0));
            spawned.add(creative);
            bucket.emptyContents(creative, level, water, null, new ItemStack(Items.WATER_BUCKET));
            assertState(helper, SOURCE, level.getBlockState(water), "creative bucket pour: no check");
            helper.succeed();
        } finally {
            GameTestPlayers.removeAll(helper, spawned);
            level.setBlock(water, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            restore(saved);
        }
    }

    // —— 冰放置禁令 ——

    private static BlockHitResult standTopHit(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(STAND_REL);
        return new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
    }

    private static BlockPlaceContext placeContext(GameTestHelper helper, Player player) {
        return new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, standTopHit(helper)));
    }

    /**
     * 机械手式放置：命中点就是目标空气格本身（机械手点的是面前那格）。Create 的
     * {@code BlockItemMixin#fixDeployerPlacement} 在命中格不可替换时对机械手返回 PASS，
     * 故机械手用例必须命中空气格，与实机一致。
     */
    static Placement tryPlaceIntoAir(GameTestHelper helper, Player player, Item item) {
        ServerLevel level = helper.getLevel();
        BlockPos target = helper.absolutePos(STAND_REL).above();
        level.setBlock(target.below(), STONE, Block.UPDATE_CLIENTS);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false);
        InteractionResult result = ((BlockItem) item).place(
                new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)));
        boolean placed = level.getBlockState(target).is(((BlockItem) item).getBlock());
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new Placement(result, placed);
    }

    /** 一次放置的结果：{@code place} 返回值与目标格是否真的放下了该方块。 */
    record Placement(InteractionResult result, boolean placed) {
        @Override
        public String toString() {
            return "result=" + result + ", placed=" + placed;
        }
    }

    /**
     * 在 STAND_REL 石头顶面直接调 {@code BlockItem#place} 放一次（禁令挂在这里；玩家右键与
     * 机械手的 {@code ItemStack#useOn} 最终都进这个方法），不经 {@code useOn} 外层的事件与
     * 建造权限分支，只验本规则。机械手用例改用 {@link #tryPlaceIntoAir}。
     */
    static Placement tryPlace(GameTestHelper helper, Player player, Item item) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(STAND_REL);
        BlockPos target = base.above();
        level.setBlock(base, STONE, Block.UPDATE_CLIENTS);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        InteractionResult result = ((BlockItem) item).place(placeContext(helper, player));
        boolean placed = level.getBlockState(target).is(((BlockItem) item).getBlock());
        level.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new Placement(result, placed);
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 100, batch = BATCH)
    public static void iceBanOnlyStopsSurvivalPlayers(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        try {
            Vec3 away = helper.absolutePos(STAND_REL).getCenter().add(4, 1, 0);
            ServerPlayer survival = GameTestPlayers.create(helper, GameType.SURVIVAL, away);
            ServerPlayer creative = GameTestPlayers.create(helper, GameType.CREATIVE, away);
            ServerPlayer adventure = GameTestPlayers.create(helper, GameType.ADVENTURE, away);
            for (Item ice : List.of(Items.ICE, Items.PACKED_ICE, Items.BLUE_ICE)) {
                Placement bySurvival = tryPlace(helper, survival, ice);
                helper.assertFalse(bySurvival.placed(), "survival must not place " + ice + " (" + bySurvival + ")");
                Placement byCreative = tryPlace(helper, creative, ice);
                helper.assertTrue(byCreative.placed(), "creative may place " + ice + " (" + byCreative + ")");
                // 冒险模式原版就不能放方块（useOn 的 mayBuild 闸），这里只断言本规则不针对它
                helper.assertFalse(IcePlacementRules.shouldBlock((BlockItem) ice, placeContext(helper, adventure)),
                        "adventure is not targeted by the ban: " + ice);
            }
            Placement stone = tryPlace(helper, survival, Items.STONE);
            helper.assertTrue(stone.placed(), "the ban only covers the three ice blocks (" + stone + ")");
            Placement byFake = tryPlace(helper, FakePlayerFactory.getMinecraft(helper.getLevel()), Items.ICE);
            helper.assertTrue(byFake.placed(), "fake players other than the Deployer are not blocked (" + byFake + ")");

            Config.ICE_PLACEMENT_BAN.set(false);
            Placement switchedOff = tryPlace(helper, survival, Items.ICE);
            helper.assertTrue(switchedOff.placed(), "icePlacementBan=false lets survival place ice (" + switchedOff + ")");
            helper.succeed();
        } finally {
            restore(saved);
        }
    }

    private LimitedWaterGameTests() {
    }
}

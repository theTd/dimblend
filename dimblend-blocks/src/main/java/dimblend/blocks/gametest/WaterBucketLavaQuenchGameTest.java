package dimblend.blocks.gametest;

import java.util.List;

import dimblend.blocks.fragile.FragileStones;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 水桶浇岩浆的服务端 GameTest：5x5 固化、外环岩浆不波及、任何位置都不出现水/含水方块、
 * 生存扣桶 / 创造留桶，以及 mixin 层（直接 {@code emptyContents}，含「点干燥面递归到岩浆格」）。
 *
 * <p>地形约定：使用 {@code empty_9x5x9}（9x5x9，x/z ∈ [0, 8]、y ∈ [0, 4]），岩浆平面放在相对
 * y = 1（相对 y = 0 是结构方块/命令方块那一行，不能碰）；7x7 场 x/z ∈ [1, 7]（相对中心格
 * (4, 1, 4) 的 dx/dz ∈ [-3, 3]），中心格恰是 5x5 淬火中心，外环（|dx| 或 |dz| = 3）保持岩浆；
 * 场外西/南紧邻半砖也在 x/z ∈ [0, 8] 模板范围内（dx/dz ∈ [-4, 4]），互不越界冲突。</p>
 * <p>注册走 {@code DimBlendBlocks} 的 {@code RegisterGameTestsEvent}（常驻），因此不加
 * {@code @GameTestHolder}，结构命名空间在 {@code @GameTest#templateNamespace} 上指定——与
 * {@code CreativeCopycatHidingGameTest} 同一口径。</p>
 */
@PrefixGameTestTemplate(false)
public final class WaterBucketLavaQuenchGameTest {

    /** 岩浆平面相对 Y：模板最底层内容格（相对 y = 0 是结构方块行，不可写）。 */
    private static final int LAVA_Y = 1;
    /** 9x5x9 模板水平中心 = 7x7 场与 5x5 淬火中心（玩家可站在其上方直视）。 */
    private static final BlockPos CENTER = new BlockPos(4, LAVA_Y, 4);

    /** 生存：视线命中岩浆 → 5x5 固化、外环不动、无水、桶变空桶。 */
    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void survivalWaterBucketOnLavaQuenchesAndEmpties(GameTestHelper helper) {
        prepareBasin(helper, true);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        aimStraightDownAtCenter(helper, player);
        BlockPos lavaAbsolute = helper.absolutePos(CENTER);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(player,
                InteractionHand.MAIN_HAND, lavaAbsolute, topFaceHit(lavaAbsolute));
        NeoForge.EVENT_BUS.post(event);

        assertIntercepted(event);
        assertQuenched(helper, true);
        assertOuterRingStillLava(helper);
        assertNoWaterOrWaterloggedAnywhere(helper);
        ItemStack after = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!after.is(Items.BUCKET)) {
            throw new GameTestAssertException("生存水桶浇岩浆后应变成空桶，实际为 " + after);
        }
        helper.succeed();
    }

    /** 创造：走 RightClickItem 分支 → 同样固化，但桶保持满水桶。 */
    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void creativeWaterBucketOnLavaKeepsFullBucket(GameTestHelper helper) {
        prepareBasin(helper, false);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        // makeMockPlayer 只覆盖 isCreative()，hasInfiniteMaterials() 读的是 abilities.instabuild。
        player.getAbilities().instabuild = true;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        aimStraightDownAtCenter(helper, player);
        PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(player,
                InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);

        assertIntercepted(event);
        assertQuenched(helper, false);
        assertNoWaterOrWaterloggedAnywhere(helper);
        ItemStack after = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!after.is(Items.WATER_BUCKET)) {
            throw new GameTestAssertException("创造水桶浇岩浆后应保留满水桶，实际为 " + after);
        }
        helper.succeed();
    }

    /**
     * Mixin 层：玩家点岩浆上方干燥石块的底面 → 原版 {@code emptyContents} 判目标不可替换后
     * 递归到相邻格（岩浆），递归那次被 mixin 接住，5x5 固化且上方石块不被浇成含水方块。
     */
    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void dryFaceClickRecursingIntoLavaQuenches(GameTestHelper helper) {
        prepareBasin(helper, false);
        ServerLevel level = helper.getLevel();
        BlockPos lavaAbsolute = helper.absolutePos(CENTER);
        BlockPos dryAbsolute = lavaAbsolute.above();
        level.setBlockAndUpdate(dryAbsolute, Blocks.STONE.defaultBlockState());

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack bucketStack = new ItemStack(Items.WATER_BUCKET);
        player.setItemInHand(InteractionHand.MAIN_HAND, bucketStack);
        // 点干燥石块的底面：方向 DOWN → 递归目标就是下面那格岩浆。
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(dryAbsolute).add(0.0, -0.5, 0.0),
                Direction.DOWN, dryAbsolute, false);
        BucketItem bucket = (BucketItem) bucketStack.getItem();
        boolean emptied = bucket.emptyContents(player, level, dryAbsolute, hit, bucketStack);

        if (!emptied) {
            throw new GameTestAssertException("岩浆格被 mixin 接管后 emptyContents 应返回 true，实际 false");
        }
        assertQuenched(helper, false);
        assertNoWaterOrWaterloggedAnywhere(helper);
        BlockState dryState = level.getBlockState(dryAbsolute);
        if (!dryState.is(Blocks.STONE)) {
            throw new GameTestAssertException("干燥石块不应被放水改写，实际为 " + dryState);
        }
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.WATER_BUCKET)) {
            throw new GameTestAssertException("emptyContents 本身不扣桶，水桶不应被改动");
        }
        helper.succeed();
    }

    /**
     * 7x7 岩浆场：{@code mixed} 为真时 5x5 内交替放源岩浆与流动岩浆（LEVEL 2 / 8），
     * 外环一律源岩浆；另在紧邻场外的两格放可含水石半砖。
     */
    private static void prepareBasin(GameTestHelper helper, boolean mixed) {
        ServerLevel level = helper.getLevel();
        // 清掉上一轮残留的瞄准路径方块（例如 dryFaceClick 写的那块石头），射线必须直达岩浆。
        level.setBlockAndUpdate(helper.absolutePos(CENTER.above()), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(CENTER.above(2)), Blocks.AIR.defaultBlockState());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean flowing = mixed
                        && Math.abs(dx) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS
                        && Math.abs(dz) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS
                        && ((dx + dz) & 1) != 0;
                level.setBlockAndUpdate(helper.absolutePos(CENTER.offset(dx, 0, dz)),
                        lavaState(flowing, dx + dz));
            }
        }
        for (BlockPos slab : waterloggableSlabs()) {
            level.setBlockAndUpdate(helper.absolutePos(slab), Blocks.STONE_SLAB.defaultBlockState());
        }
    }

    /** 源岩浆 = LEVEL 0；流动岩浆交替 LEVEL 2（非下落）与 LEVEL 8（下落），两者 {@code isSource()} 都为假。 */
    private static BlockState lavaState(boolean flowing, int offset) {
        if (!flowing) {
            return Blocks.LAVA.defaultBlockState();
        }
        int fluidLevel = Math.floorMod(offset, 4) == 1 ? 2 : 8;
        return Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, fluidLevel);
    }

    /** 场外紧邻的两个可含水石半砖（西侧、南侧各一）。 */
    private static List<BlockPos> waterloggableSlabs() {
        return List.of(CENTER.offset(-4, 0, 0), CENTER.offset(0, 0, 4));
    }

    /** 站在中心格上方（结构内，视高 1.62 仍在模板内）垂直下看：射线首格必是中心岩浆格。 */
    private static void aimStraightDownAtCenter(GameTestHelper helper, Player player) {
        BlockPos absolute = helper.absolutePos(CENTER);
        player.setPos(absolute.getX() + 0.5D, absolute.getY() + 1.0D, absolute.getZ() + 0.5D);
        player.setYRot(0.0F);
        player.setXRot(90.0F);
    }

    private static BlockHitResult topFaceHit(BlockPos lavaAbsolute) {
        return new BlockHitResult(Vec3.atBottomCenterOf(lavaAbsolute).add(0.0, 1.0, 0.0),
                Direction.UP, lavaAbsolute, false);
    }

    private static void assertIntercepted(ICancellableEvent event) {
        if (!event.isCanceled()) {
            throw new GameTestAssertException("水桶浇岩浆必须取消原版交互（否则会放水），实际未取消");
        }
    }

    /** 5x5：源岩浆格变黑曜石，流动格变易碎石头；每格都断言，别用「至少一格」糊过去。 */
    private static void assertQuenched(GameTestHelper helper, boolean mixed) {
        ServerLevel level = helper.getLevel();
        for (BlockPos rel : WaterBucketLavaQuenchMath.horizontalSquare5x5(CENTER)) {
            int dx = rel.getX() - CENTER.getX();
            int dz = rel.getZ() - CENTER.getZ();
            boolean flowing = mixed && ((dx + dz) & 1) != 0;
            Block expected = flowing ? FragileStones.FRAGILE_STONE.get() : Blocks.OBSIDIAN;
            BlockState actual = level.getBlockState(helper.absolutePos(rel));
            if (!actual.is(expected)) {
                throw new GameTestAssertException(
                        "5x5 淬火格 " + rel + "（dx=" + dx + " dz=" + dz + "）应为 " + expected + "，实际为 " + actual);
            }
        }
    }

    /** 7x7 场里 |dx| 或 |dz| = 3 的外环必须还是岩浆。 */
    private static void assertOuterRingStillLava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS
                        && Math.abs(dz) <= WaterBucketLavaQuenchMath.QUENCH_RADIUS) {
                    continue;
                }
                BlockPos rel = CENTER.offset(dx, 0, dz);
                BlockState actual = level.getBlockState(helper.absolutePos(rel));
                if (!actual.is(Blocks.LAVA)) {
                    throw new GameTestAssertException("5x5 外环岩浆不应被改写：" + rel + " 实际为 " + actual);
                }
            }
        }
    }

    /** 整片作业区（含场外半砖）任何位置都不允许出现水或含水方块。 */
    private static void assertNoWaterOrWaterloggedAnywhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos absolute = helper.absolutePos(CENTER.offset(dx, dy, dz));
                    BlockState state = level.getBlockState(absolute);
                    if (state.is(Blocks.WATER) || state.getFluidState().is(FluidTags.WATER)) {
                        throw new GameTestAssertException("浇岩浆不允许放水，却在 " + absolute + " 留下 " + state);
                    }
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED)
                            && state.getValue(BlockStateProperties.WATERLOGGED)) {
                        throw new GameTestAssertException("浇岩浆不允许产生含水方块，" + absolute + " 为 " + state);
                    }
                }
            }
        }
    }

    private WaterBucketLavaQuenchGameTest() {
    }
}

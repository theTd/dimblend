package dimblend.blocks.gametest;

import java.util.List;

import dimblend.blocks.fragile.FragileStones;
import dimblend.blocks.fragile.LavaQuenchSmokeTrail;
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
 * 水桶 / 蓝冰冷却岩浆的服务端 GameTest：
 * <ul>
 *   <li>右键岩浆源头：3x3x3 内所有源头固化为黑曜石，内部岩浆流与外圈源头保持原样；</li>
 *   <li>右键 16 格内岩浆流（含蓝冰测试）：源头变黑曜石、沿途岩浆流变易碎石头，蓝冰减 1 且不放蓝冰方块；</li>
 *   <li>右键超出 16 格岩浆流：方块不固化，触发沿途烟雾回溯轨迹；</li>
 *   <li>创造模式保留水桶，以及 {@code emptyContents} 干燥面递归兜底。</li>
 * </ul>
 */
@PrefixGameTestTemplate(false)
public final class WaterBucketLavaQuenchGameTest {

    private static final int LAVA_Y = 1;
    private static final BlockPos CENTER = new BlockPos(4, LAVA_Y, 4);

    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void survivalWaterBucketOnSourceQuenches3x3x3SourcesOnly(GameTestHelper helper) {
        clearWorkspace(helper);
        ServerLevel level = helper.getLevel();
        BlockPos flowingInside = CENTER.offset(1, 0, 0);
        BlockPos upperSource = CENTER.offset(-1, 1, 0);
        BlockPos outerSource = CENTER.offset(2, 0, 0);

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos rel = CENTER.offset(dx, 0, dz);
                BlockState state = rel.equals(flowingInside)
                        ? Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2)
                        : Blocks.LAVA.defaultBlockState();
                level.setBlockAndUpdate(helper.absolutePos(rel), state);
            }
        }
        level.setBlockAndUpdate(helper.absolutePos(upperSource), Blocks.LAVA.defaultBlockState());
        for (BlockPos slab : waterloggableSlabs()) {
            level.setBlockAndUpdate(helper.absolutePos(slab), Blocks.STONE_SLAB.defaultBlockState());
        }

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        aimStraightDownAtCenter(helper, player);
        BlockPos lavaAbsolute = helper.absolutePos(CENTER);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, lavaAbsolute, topFaceHit(lavaAbsolute));
        NeoForge.EVENT_BUS.post(event);

        assertIntercepted(event);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos rel = CENTER.offset(dx, 0, dz);
                BlockState actual = level.getBlockState(helper.absolutePos(rel));
                if (rel.equals(flowingInside)) {
                    if (!actual.is(Blocks.LAVA)) {
                        throw new GameTestAssertException("3x3x3 内非源头岩浆流应保持岩浆，实际为 " + actual);
                    }
                } else if (!actual.is(Blocks.OBSIDIAN)) {
                    throw new GameTestAssertException("3x3x3 内岩浆源头 " + rel + " 应变为黑曜石，实际为 " + actual);
                }
            }
        }
        if (!level.getBlockState(helper.absolutePos(upperSource)).is(Blocks.OBSIDIAN)) {
            throw new GameTestAssertException("3x3x3 上层岩浆源头应变为黑曜石");
        }
        if (!level.getBlockState(helper.absolutePos(outerSource)).is(Blocks.LAVA)) {
            throw new GameTestAssertException("3x3x3 外圈岩浆源头不应被波及");
        }
        assertNoWaterOrWaterloggedAnywhere(helper);
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.BUCKET)) {
            throw new GameTestAssertException("生存水桶冷却岩浆后应变成空桶");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void survivalBlueIceTracesFlowWithin16AndConsumesOne(GameTestHelper helper) {
        clearWorkspace(helper);
        ServerLevel level = helper.getLevel();
        BlockPos sourceRel = new BlockPos(1, LAVA_Y, 4);
        BlockPos flow1Rel = new BlockPos(2, LAVA_Y, 4);
        BlockPos flow2Rel = new BlockPos(3, LAVA_Y, 4);
        BlockPos clickedRel = CENTER; // (4, 1, 4)

        level.setBlockAndUpdate(helper.absolutePos(sourceRel), Blocks.LAVA.defaultBlockState());
        level.setBlockAndUpdate(helper.absolutePos(flow1Rel), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 2));
        level.setBlockAndUpdate(helper.absolutePos(flow2Rel), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 4));
        level.setBlockAndUpdate(helper.absolutePos(clickedRel), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 6));

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLUE_ICE, 3));
        aimStraightDownAtCenter(helper, player);
        BlockPos clickedAbs = helper.absolutePos(clickedRel);
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, clickedAbs, topFaceHit(clickedAbs));
        NeoForge.EVENT_BUS.post(event);

        assertIntercepted(event);
        if (!level.getBlockState(helper.absolutePos(sourceRel)).is(Blocks.OBSIDIAN)) {
            throw new GameTestAssertException("16 格内上游源头应变为黑曜石");
        }
        for (BlockPos flowRel : List.of(flow1Rel, flow2Rel, clickedRel)) {
            BlockState actual = level.getBlockState(helper.absolutePos(flowRel));
            if (!actual.is(FragileStones.FRAGILE_STONE.get())) {
                throw new GameTestAssertException("回溯路径上的岩浆流 " + flowRel + " 应变为易碎石头，实际为 " + actual);
            }
        }
        ItemStack after = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!after.is(Items.BLUE_ICE) || after.getCount() != 2) {
            throw new GameTestAssertException("生存模式使用蓝冰后数量应减 1（3 -> 2），实际为 " + after);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void flowOver16SchedulesSmokeWithoutSolidifying(GameTestHelper helper) {
        clearWorkspace(helper);
        LavaQuenchSmokeTrail.clearActiveTrails();
        ServerLevel level = helper.getLevel();
        BlockPos clickedAbs = helper.absolutePos(CENTER);
        for (int dy = 0; dy < 18; dy++) {
            level.setBlockAndUpdate(clickedAbs.above(dy), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
        }
        BlockPos sourceAbs = clickedAbs.above(18);
        level.setBlockAndUpdate(sourceAbs, Blocks.LAVA.defaultBlockState());

        try {
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
            // 站在南侧看向中心格的岩浆瀑布底部（eye = cy + 1.62, dz = 1.5, xRot = 35° -> y ≈ cy + 0.57 命中 clickedAbs）
            player.setPos(clickedAbs.getX() + 0.5D, clickedAbs.getY(), clickedAbs.getZ() + 2.5D);
            player.setYRot(180.0F);
            player.setYHeadRot(180.0F);
            player.setXRot(35.0F);

            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, InteractionHand.MAIN_HAND, clickedAbs, topFaceHit(clickedAbs));
            NeoForge.EVENT_BUS.post(event);

            assertIntercepted(event);
            if (!level.getBlockState(clickedAbs).is(Blocks.LAVA) || !level.getBlockState(sourceAbs).is(Blocks.LAVA)) {
                throw new GameTestAssertException("超出 16 格的岩浆流与源头都不应被固化");
            }
            if (LavaQuenchSmokeTrail.activeTrailCount() < 1) {
                throw new GameTestAssertException("超出 16 格时应登记沿途烟雾与熄灭音效轨迹");
            }
            ItemStack expected = WaterBucketLavaQuenchMath.CONSUME_WHEN_SOURCE_OVER_16
                    ? new ItemStack(Items.BUCKET)
                    : new ItemStack(Items.WATER_BUCKET);
            if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(expected.getItem())) {
                throw new GameTestAssertException("超出 16 格时桶状态不符合预期");
            }
        } finally {
            LavaQuenchSmokeTrail.clearActiveTrails();
            for (int dy = 0; dy <= 18; dy++) {
                level.setBlockAndUpdate(clickedAbs.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void creativeWaterBucketOnLavaKeepsFullBucket(GameTestHelper helper) {
        clearWorkspace(helper);
        ServerLevel level = helper.getLevel();
        level.setBlockAndUpdate(helper.absolutePos(CENTER), Blocks.LAVA.defaultBlockState());

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.getAbilities().instabuild = true;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET));
        aimStraightDownAtCenter(helper, player);
        PlayerInteractEvent.RightClickItem event = new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        NeoForge.EVENT_BUS.post(event);

        assertIntercepted(event);
        if (!level.getBlockState(helper.absolutePos(CENTER)).is(Blocks.OBSIDIAN)) {
            throw new GameTestAssertException("创造模式水桶冷却源头应变为黑曜石");
        }
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(Items.WATER_BUCKET)) {
            throw new GameTestAssertException("创造模式水桶应保留满水桶");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_9x5x9", templateNamespace = "dimblend_blocks")
    public static void dryFaceClickRecursingIntoLavaQuenches(GameTestHelper helper) {
        clearWorkspace(helper);
        ServerLevel level = helper.getLevel();
        BlockPos lavaAbsolute = helper.absolutePos(CENTER);
        BlockPos dryAbsolute = lavaAbsolute.above();
        level.setBlockAndUpdate(lavaAbsolute, Blocks.LAVA.defaultBlockState());
        level.setBlockAndUpdate(dryAbsolute, Blocks.STONE.defaultBlockState());

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack bucketStack = new ItemStack(Items.WATER_BUCKET);
        player.setItemInHand(InteractionHand.MAIN_HAND, bucketStack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(dryAbsolute).add(0.0, -0.5, 0.0),
                Direction.DOWN, dryAbsolute, false);
        BucketItem bucket = (BucketItem) bucketStack.getItem();
        boolean emptied = bucket.emptyContents(player, level, dryAbsolute, hit, bucketStack);

        if (!emptied) {
            throw new GameTestAssertException("岩浆格被 mixin 接管后 emptyContents 应返回 true");
        }
        if (!level.getBlockState(lavaAbsolute).is(Blocks.OBSIDIAN)) {
            throw new GameTestAssertException("递归命中岩浆源头应变为黑曜石");
        }
        assertNoWaterOrWaterloggedAnywhere(helper);
        helper.succeed();
    }

    private static void clearWorkspace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    level.setBlockAndUpdate(helper.absolutePos(CENTER.offset(dx, dy, dz)), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static List<BlockPos> waterloggableSlabs() {
        return List.of(CENTER.offset(-4, 0, 0), CENTER.offset(0, 0, 4));
    }

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
            throw new GameTestAssertException("水桶/蓝冰冷却岩浆必须取消原版交互，实际未取消");
        }
    }

    private static void assertNoWaterOrWaterloggedAnywhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos absolute = helper.absolutePos(CENTER.offset(dx, dy, dz));
                    BlockState state = level.getBlockState(absolute);
                    if (state.is(Blocks.WATER) || state.getFluidState().is(FluidTags.WATER)) {
                        throw new GameTestAssertException("冷却岩浆不允许放水，却在 " + absolute + " 留下 " + state);
                    }
                    if (state.hasProperty(BlockStateProperties.WATERLOGGED)
                            && state.getValue(BlockStateProperties.WATERLOGGED)) {
                        throw new GameTestAssertException("冷却岩浆不允许产生含水方块，" + absolute + " 为 " + state);
                    }
                }
            }
        }
    }

    private WaterBucketLavaQuenchGameTest() {
    }
}

package dimblend.experience.gametest;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G3 新口径 + 三种冰规则的运行时织入检查（bypass/归因 ThreadLocal 与事件拦截
 * 都是行为级改动，纯函数单测覆盖不到）：
 * 三种冰无精准破坏产源水且绕过降级；精准采集不产水；无归因写入与生存玩家倒水
 * 仍降级；创造玩家倒水保留源水；生存玩家放冰被拦、创造放行。
 *
 * <p>全部用例体同步执行（无 runAfterDelay），开头先把共享配置置为期望值、
 * finally 还原 {@code rotatingDimensionId}——单线程顺序调用下无并发互踩窗口。
 * GameTest 跑在主世界，G3/冰规则的维度门靠把 {@code rotatingDimensionId}
 * 临时指向 minecraft:overworld 通过。</p>
 *
 * <p>破坏路径测到 {@code Block#playerDestroy} 层（逻辑全部挂在这里；
 * {@code ServerPlayerGameMode#destroyBlock} 全链路的创造门属原版行为，
 * 源码已核实，不在此重复）。模板复用 item_drain_refill 空平台。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class IceWaterRulesGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String DEFAULT_ROTATING_ID = "dimblend:rotating";

    private static void setupConfig() {
        Config.ISOLATED_WATER_DOWNGRADE.set(true);
        Config.ICE_BREAK_WATER_SOURCE.set(true);
        Config.ICE_PLACEMENT_BAN.set(true);
        Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
    }

    private static void restoreConfig() {
        Config.ROTATING_DIMENSION_ID.set(DEFAULT_ROTATING_ID);
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void packedIceBreakYieldsSource(GameTestHelper helper) {
        setupConfig();
        try {
            breakIceAndAssert(helper, Blocks.PACKED_ICE, new ItemStack(Items.DIAMOND_PICKAXE), true,
                    "packed ice broken without silk touch must leave a water source (bypasses G3)");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void blueIceBreakYieldsSource(GameTestHelper helper) {
        setupConfig();
        try {
            breakIceAndAssert(helper, Blocks.BLUE_ICE, new ItemStack(Items.DIAMOND_PICKAXE), true,
                    "blue ice broken without silk touch must leave a water source (bypasses G3)");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void silkTouchPackedIceBreaksDry(GameTestHelper helper) {
        setupConfig();
        try {
            breakIceAndAssert(helper, Blocks.PACKED_ICE, silkTouchPick(helper), false,
                    "silk touch must prevent water from packed ice");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void frostedIceBreakDoesNotBypass(GameTestHelper helper) {
        setupConfig();
        try {
            // frosted_ice 不在三种冰内（冰霜行者可再生，放行等于无限水）：
            // 破坏产水走继承的 IceBlock.playerDestroy，不得享受 bypass，孤立写入仍降级
            ServerLevel level = helper.getLevel();
            BlockPos baseRel = new BlockPos(1, 1, 1);
            helper.setBlock(baseRel, Blocks.STONE.defaultBlockState());
            BlockPos iceRel = baseRel.above();
            helper.setBlock(iceRel, Blocks.FROSTED_ICE.defaultBlockState());
            BlockPos iceAbs = helper.absolutePos(iceRel);
            BlockState before = level.getBlockState(iceAbs);
            level.removeBlock(iceAbs, false);
            Blocks.FROSTED_ICE.playerDestroy(level, helper.makeMockPlayer(GameType.SURVIVAL),
                    iceAbs, before, null, new ItemStack(Items.DIAMOND_PICKAXE));
            BlockState after = level.getBlockState(iceAbs);
            helper.assertTrue(isWater7(after),
                    "frosted ice break water must NOT bypass G3 (got " + after + ")");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void regularIceBreakKeepsSource(GameTestHelper helper) {
        setupConfig();
        try {
            // 原版 IceBlock.playerDestroy 产水 + bypass 放行：结果必须是源水而非降级的 water7
            breakIceAndAssert(helper, Blocks.ICE, new ItemStack(Items.DIAMOND_PICKAXE), true,
                    "regular ice break water must bypass G3 and stay a source");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void unattributedSourceStillDowngrades(GameTestHelper helper) {
        setupConfig();
        try {
            // Q1 回归锁：无归因写入（模拟发射器/管道/自然成池）仍降级
            BlockPos rel = new BlockPos(1, 1, 1);
            helper.getLevel().setBlock(helper.absolutePos(rel), Blocks.WATER.defaultBlockState(), 3);
            helper.assertTrue(isWater7(helper.getBlockState(rel)),
                    "unattributed water source write must still downgrade to water7");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void survivalBucketDowngradesCreativeBucketKeeps(GameTestHelper helper) {
        setupConfig();
        try {
            ServerLevel level = helper.getLevel();
            BlockPos survivalAbs = helper.absolutePos(new BlockPos(1, 1, 1));
            // 模板为 3x3x3 空箱（相对坐标 0..2），两个倒水点都要在界内——越界落点
            // 可能是世界地面实体方块，emptyContents 会拒绝写入
            BlockPos creativeAbs = helper.absolutePos(new BlockPos(2, 1, 1));
            BucketItem bucket = (BucketItem) Items.WATER_BUCKET;
            bucket.emptyContents(helper.makeMockPlayer(GameType.SURVIVAL), level, survivalAbs, null,
                    new ItemStack(Items.WATER_BUCKET));
            bucket.emptyContents(helper.makeMockPlayer(GameType.CREATIVE), level, creativeAbs, null,
                    new ItemStack(Items.WATER_BUCKET));
            helper.assertTrue(isWater7(level.getBlockState(survivalAbs)),
                    "survival player bucket water must downgrade to water7 (got "
                            + level.getBlockState(survivalAbs) + ")");
            helper.assertTrue(isSource(level.getBlockState(creativeAbs)),
                    "creative player bucket water must stay a source (got "
                            + level.getBlockState(creativeAbs) + ")");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    @GameTest(template = TEMPLATE, templateNamespace = "dimblend_experience", timeoutTicks = 100, batch = "ice_water")
    public static void survivalIcePlacementDeniedCreativeAllowed(GameTestHelper helper) {
        setupConfig();
        try {
            BlockPos abs = helper.absolutePos(new BlockPos(1, 1, 1));
            TriState survivalUseItem = postRightClickWithIce(helper.makeMockPlayer(GameType.SURVIVAL), abs);
            TriState creativeUseItem = postRightClickWithIce(helper.makeMockPlayer(GameType.CREATIVE), abs);
            helper.assertTrue(survivalUseItem == TriState.FALSE,
                    "survival player ice placement must be denied (useItem=FALSE)");
            helper.assertTrue(creativeUseItem != TriState.FALSE,
                    "creative player ice placement must not be denied");
            helper.succeed();
        } finally {
            restoreConfig();
        }
    }

    /** 模拟挖掘顺序（removeBlock → playerDestroy）破坏一块冰，断言原位是否为水源。 */
    private static void breakIceAndAssert(GameTestHelper helper, Block iceBlock, ItemStack tool,
            boolean expectSource, String message) {
        ServerLevel level = helper.getLevel();
        BlockPos baseRel = new BlockPos(1, 1, 1);
        helper.setBlock(baseRel, Blocks.STONE.defaultBlockState());
        BlockPos iceRel = baseRel.above();
        helper.setBlock(iceRel, iceBlock.defaultBlockState());
        BlockPos iceAbs = helper.absolutePos(iceRel);
        BlockState before = level.getBlockState(iceAbs);
        level.removeBlock(iceAbs, false);
        iceBlock.playerDestroy(level, helper.makeMockPlayer(GameType.SURVIVAL), iceAbs, before, null, tool);
        BlockState after = level.getBlockState(iceAbs);
        helper.assertTrue(isSource(after) == expectSource,
                message + " (got " + after + ")");
    }

    private static TriState postRightClickWithIce(Player player, BlockPos abs) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PACKED_ICE));
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, abs,
                new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false));
        NeoForge.EVENT_BUS.post(event);
        return event.getUseItem();
    }

    private static ItemStack silkTouchPick(GameTestHelper helper) {
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        Holder<Enchantment> silkTouch = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        pick.enchant(silkTouch, 1);
        return pick;
    }

    private static boolean isSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    private static boolean isWater7(BlockState state) {
        return state.is(Blocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 1;
    }

    private IceWaterRulesGameTests() {
    }
}

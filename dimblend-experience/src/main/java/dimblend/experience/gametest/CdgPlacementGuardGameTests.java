package dimblend.experience.gametest;

import java.util.List;

import com.jesz.createdieselgenerators.CDGBlocks;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import com.simibubi.create.AllBlocks;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * B8 柴油机放置守卫（{@code CdgPlacementGuard}）运行时验证：放到运转中网络上的柴油机
 * 下一 tick 被破坏并掉落；放到静止网络上存活。需要 CDG 进 dev 运行：
 * {@code ./gradlew :dimblend-experience:runGameTestServer -PwithCdg}；仅 CDG 在场时注册。
 * 模板复用 {@code item_drain_refill}（3×3×3 全空平台），工作层 y=1。
 *
 * <p>放置必须走 {@code ItemStack#useOn}：21.1 的 {@code EntityPlaceEvent} 钩子在
 * {@code CommonHooks.onPlaceItemIntoWorld}（ItemStack.useOn 服务端分支），直接调
 * {@code BlockItem#place} 不触发事件（与 {@code LimitedWaterGameTests} 的放置捷径不同）。
 * 例外：巨型机用例因朝向回退路径在替身玩家上不可靠，改为 setBlock 显式定向后
 * 手动补发事件（详见该用例行内注释）。</p>
 *
 * <p>不加 {@code @GameTestHolder}：NeoForge 会按扫描数据自动注册所有带该注解的类，
 * 无 CDG 时加载本类即 NoClassDefFoundError；只由 DimBlend 在 CDG 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class CdgPlacementGuardGameTests {

    private static final BlockPos WEST = new BlockPos(0, 1, 1);
    private static final BlockPos MID = new BlockPos(1, 1, 1);
    private static final BlockPos EAST = new BlockPos(2, 1, 1);

    private CdgPlacementGuardGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void engineOnRunningNetworkIsDestroyed(GameTestHelper helper) {
        boolean guard = Config.DIESEL_PLACEMENT_GUARD.get();
        Config.DIESEL_PLACEMENT_GUARD.set(true);
        // 马达 → 轴：先起转，再把柴油机放到轴尾（轴两侧同轴都能接，FACING 方向不影响连通）。
        // 配置只在 onPlace 事件时刻读取，放置完成后即可恢复
        helper.setBlock(WEST, motor(Direction.EAST));
        helper.setBlock(MID, shaftX());
        helper.runAfterDelay(5, () -> {
            ServerPlayer player = GameTestPlayers.create(helper, GameType.SURVIVAL,
                    Vec3.atBottomCenterOf(helper.absolutePos(EAST.above(2))));
            try {
                place(helper, player, new ItemStack(CDGBlocks.DIESEL_ENGINE.asItem()), MID, Direction.EAST);
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(EAST))
                                .is(CDGBlocks.DIESEL_ENGINE.get()),
                        "precondition: engine placement itself must succeed");
            } finally {
                Config.DIESEL_PLACEMENT_GUARD.set(guard);
            }
            helper.runAfterDelay(3, () -> {
                assertDropped(helper, EAST, CDGBlocks.DIESEL_ENGINE.asItem(),
                        "engine placed on a running network");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void engineOnIdleNetworkSurvives(GameTestHelper helper) {
        boolean guard = Config.DIESEL_PLACEMENT_GUARD.get();
        Config.DIESEL_PLACEMENT_GUARD.set(true);
        // 只有静止的轴（无动力源）：网络转速 0，放置必须放行
        helper.setBlock(MID, shaftX());
        ServerPlayer player = GameTestPlayers.create(helper, GameType.SURVIVAL,
                Vec3.atBottomCenterOf(helper.absolutePos(EAST.above(2))));
        try {
            place(helper, player, new ItemStack(CDGBlocks.DIESEL_ENGINE.asItem()), MID, Direction.EAST);
        } finally {
            Config.DIESEL_PLACEMENT_GUARD.set(guard);
        }
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(EAST))
                            .is(CDGBlocks.DIESEL_ENGINE.get()),
                    "engine placed on an idle network must survive");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void hugeEngineOnRunningNetworkIsDestroyed(GameTestHelper helper) {
        boolean guard = Config.DIESEL_PLACEMENT_GUARD.get();
        Config.DIESEL_PLACEMENT_GUARD.set(true);
        // 巨型机的轴在 FACING 前 2 格且轴心必须与 FACING 垂直（同轴不转换，实测
        // westBlock 停在 create:shaft[axis=x]）：Z 轴传动杆放 WEST，马达在 (0,1,0)
        // 朝南驱动；巨型机 FACING 朝西。useOn 放置的朝向回退路径在替身玩家上不可靠
        // （Sable 的 UseOnContext mixin 介入，实测 yRot=-90 仍落出 facing=north），
        // 故 setBlock 显式定向后手动补发 EntityPlaceEvent——真实放置→事件链路已由
        // 普通机用例锁定，本用例只锁巨型机特有的 getShaft().getSpeed() 判据分支。
        // 配置只在事件时刻读取，补发事件完成后即可恢复
        helper.setBlock(WEST, shaftZ());
        helper.setBlock(new BlockPos(0, 1, 0), motor(Direction.SOUTH));
        helper.runAfterDelay(5, () -> {
            helper.setBlock(EAST, CDGBlocks.HUGE_DIESEL_ENGINE.get().defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.WEST));
            ServerLevel level = helper.getLevel();
            BlockPos absEast = helper.absolutePos(EAST);
            ServerPlayer player = GameTestPlayers.create(helper, GameType.SURVIVAL,
                    Vec3.atBottomCenterOf(helper.absolutePos(EAST.above(2))));
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(
                    BlockSnapshot.create(level.dimension(), level, absEast),
                    level.getBlockState(helper.absolutePos(EAST.below())), player));
            try {
                helper.assertTrue(level.getBlockState(absEast).is(CDGBlocks.HUGE_DIESEL_ENGINE.get()),
                        "precondition: huge engine must be placed facing its shaft");
            } finally {
                Config.DIESEL_PLACEMENT_GUARD.set(guard);
            }
            helper.runAfterDelay(3, () -> {
                // 失败信息带上轴读数：区分「守卫未拆」与「轴转速为 0 被放行」
                HugeDieselEngineBlockEntity engine =
                        level.getBlockEntity(absEast) instanceof HugeDieselEngineBlockEntity e ? e : null;
                PoweredEngineShaftBlockEntity shaft = engine == null ? null : engine.getShaft();
                helper.assertTrue(level.getBlockState(absEast).isAir(),
                        "huge engine placed on a running network: block must be destroyed"
                                + " by the placement guard, got " + level.getBlockState(absEast)
                                + " shaft=" + (shaft == null ? "null" : "speed=" + shaft.getSpeed()));
                assertDropped(helper, EAST, CDGBlocks.HUGE_DIESEL_ENGINE.asItem(),
                        "huge engine placed on a running network");
                helper.succeed();
            });
        });
    }

    private static BlockState motor(Direction facing) {
        return AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing);
    }

    private static BlockState shaftX() {
        return AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    }

    private static BlockState shaftZ() {
        return AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
    }

    /** 经 {@code ItemStack#useOn} 完整路径放置（EntityPlaceEvent 只挂在这条路径上）。 */
    private static net.minecraft.world.InteractionResult place(GameTestHelper helper, ServerPlayer player,
            ItemStack stack, BlockPos clickedRel, Direction clickedFace) {
        BlockPos clickedAbs = helper.absolutePos(clickedRel);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clickedAbs), clickedFace, clickedAbs, false);
        return stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    /** 方块必须已被守卫拆除，且按 loot 掉出对应物品。 */
    private static void assertDropped(GameTestHelper helper, BlockPos rel, net.minecraft.world.item.Item expected,
            String stage) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(rel);
        helper.assertTrue(level.getBlockState(abs).isAir(),
                stage + ": block must be destroyed by the placement guard, got "
                        + level.getBlockState(abs));
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                AABB.ofSize(Vec3.atCenterOf(abs), 4.0, 4.0, 4.0),
                entity -> entity.getItem().is(expected));
        helper.assertFalse(drops.isEmpty(), stage + ": destroyed engine must drop its item");
    }
}

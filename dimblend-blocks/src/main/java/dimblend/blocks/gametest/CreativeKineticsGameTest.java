package dimblend.blocks.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.create.CreativeKinetics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * K 板块扳手语义的服务端 GameTest（实测报告：生存扳手点创造齿轮疑似变旋转）。
 * 直接驱动 {@code WrenchItem.useOn} → IWrenchable 派发链（与服务端交互管线同一条），
 * 生存假玩家（{@code makeMockPlayer(GameType.SURVIVAL)}，mayBuild=true）。
 */
@GameTestHolder(DimBlendBlocks.MODID)
public final class CreativeKineticsGameTest {

    /** 生存扳手普通右键：创造齿轮 → 创造传动杆（互转右半）。 */
    @GameTest(template = "empty_3x3")
    public static void survivalWrenchTurnsCogwheelIntoShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_COGWHEEL.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, false);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_SHAFT.get()),
                () -> "生存扳手点齿轮后应为 creative_shaft（useOn 返回 " + result + "）");
        helper.succeed();
    }

    /**
     * 守卫负向分支（复核验证轮补充）：生存经 catnip placeInWorld 旁路放置仍被拦并回滚。
     * 场景：手持创造轴物品点原版轴——原版轴的 PoleHelper 物品谓词
     * （instanceof AbstractSimpleShaftBlock）命中创造轴物品，placeInWorld 绕过
     * canPlace/getStateForPlacement，EntityPlaceEvent 快照旧状态=空气 → 守卫取消。
     * 点击点取上半格（+0.4），使 UP 候选严格最近、目标确定性锁定 pos.above()；
     * 返回值断言 FAIL（placeInWorld 被阻止时返回 FAIL，字节码核实）以区分
     * 「helper 触发且被拦」与「helper 未触发的空转 PASS_TO_DEFAULT」。
     */
    @GameTest(template = "empty_3x3")
    public static void survivalPlacementViaPoleHelperIsBlocked(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack shaftItem = CreativeKinetics.CREATIVE_SHAFT_ITEM.get().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, shaftItem);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, 0.4, 0), Direction.UP,
                absolute, true);
        ItemInteractionResult result = helper.getBlockState(pos)
                .useItemOn(shaftItem, helper.getLevel(), player, InteractionHand.MAIN_HAND, hit);
        helper.assertBlockState(pos.above(), BlockState::isAir,
                () -> "生存经 PoleHelper 旁路放置创造轴应被守卫回滚，上方格仍应为空气（useItemOn 返回 "
                        + result + "）");
        if (result != ItemInteractionResult.FAIL) {
            throw new GameTestAssertException(
                    "守卫拦截后 useItemOn 应返回 FAIL，实际返回 " + result);
        }
        helper.succeed();
    }

    /** 生存扳手普通右键：创造传动杆 → 创造齿轮（互转左半）。 */
    @GameTest(template = "empty_3x3")
    public static void survivalWrenchTurnsShaftIntoCogwheel(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, false);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_COGWHEEL.get()),
                () -> "生存扳手点传动杆后应为 creative_cogwheel（useOn 返回 " + result + "）");
        helper.succeed();
    }

    /** 生存潜行扳手：不可拆卸——裸传动杆必须还在（扳手拆除路径已禁用）。 */
    @GameTest(template = "empty_3x3")
    public static void survivalSneakWrenchDoesNotDismantleShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, true);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_SHAFT.get()),
                () -> "潜行扳手后传动杆不应被拆掉（useOn 返回 " + result + "）");
        helper.succeed();
    }

    /** 生存潜行扳手拆壳：套壳传动杆 → 创造传动杆（不消耗壳/不返还壳，与 Create 对称）。 */
    @GameTest(template = "empty_3x3")
    public static void survivalSneakWrenchUnencasesShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_ANDESITE_ENCASED_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, true);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_SHAFT.get()),
                () -> "潜行扳手拆壳后应为 creative_shaft（useOn 返回 " + result + "）");
        helper.succeed();
    }

    /** 创造扳手普通右键：恢复原版旋转语义——轴变点击面轴向，方块不互转（2026-09-29 拍板）。 */
    @GameTest(template = "empty_3x3")
    public static void creativeWrenchRotatesShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        wrench(helper, pos, false, GameType.CREATIVE, Direction.NORTH);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_SHAFT.get())
                        && state.getValue(RotatedPillarKineticBlock.AXIS) == Direction.Axis.X,
                () -> "创造扳手点传动杆应绕点击面旋转（Y 绕 Z 轴→X）且不互转成齿轮");
        helper.succeed();
    }

    /** 创造扳手普通右键：齿轮同样旋转不互转。 */
    @GameTest(template = "empty_3x3")
    public static void creativeWrenchRotatesCogwheel(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_COGWHEEL.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        wrench(helper, pos, false, GameType.CREATIVE, Direction.NORTH);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_COGWHEEL.get())
                        && state.getValue(RotatedPillarKineticBlock.AXIS) == Direction.Axis.X,
                () -> "创造扳手点齿轮应绕点击面旋转（Y 绕 Z 轴→X）且不互转成传动杆");
        helper.succeed();
    }

    /** 创造扳手普通右键：套壳轴恢复上游旋转语义。 */
    @GameTest(template = "empty_3x3")
    public static void creativeWrenchRotatesEncasedShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_ANDESITE_ENCASED_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        wrench(helper, pos, false, GameType.CREATIVE, Direction.NORTH);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_ANDESITE_ENCASED_SHAFT.get())
                        && state.getValue(RotatedPillarKineticBlock.AXIS) == Direction.Axis.X,
                () -> "创造扳手点套壳轴应绕点击面旋转（Y 绕 Z 轴→X）且不拆壳");
        helper.succeed();
    }

    /** 创造潜行扳手：恢复原版拆卸语义——移除不掉落（2026-09-29 追加拍板）。 */
    @GameTest(template = "empty_3x3")
    public static void creativeSneakWrenchDismantlesShaft(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_SHAFT.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, true, GameType.CREATIVE, Direction.UP);
        helper.assertBlockState(pos, BlockState::isAir,
                () -> "创造潜行扳手应可拆除传动杆（原版语义，useOn 返回 " + result + "）");
        helper.succeed();
    }

    /** 创造扳手点 encased 齿轮端面：恢复上游 TOP_SHAFT 翻转语义（复核验证轮补充）。 */
    @GameTest(template = "empty_3x3")
    public static void creativeWrenchTogglesEncasedCogwheelShaftEnd(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        wrench(helper, pos, false, GameType.CREATIVE, Direction.UP);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get())
                        && state.getValue(com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogwheelBlock.TOP_SHAFT),
                () -> "创造扳手点 encased 齿轮端面应翻转 TOP_SHAFT 且方块不变");
        helper.succeed();
    }

    /** 生存扳手点 encased：普通右键无效（只能潜行拆壳），方块原样（复核验证轮补充）。 */
    @GameTest(template = "empty_3x3")
    public static void survivalWrenchOnEncasedCogwheelIsNoop(GameTestHelper helper) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get().defaultBlockState()
                .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.Y));
        InteractionResult result = wrench(helper, pos, false);
        helper.assertBlockState(pos, state -> state.is(CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get())
                        && state.getValue(RotatedPillarKineticBlock.AXIS) == Direction.Axis.Y
                        && !state.getValue(com.simibubi.create.content.kinetics.simpleRelays.encased.EncasedCogwheelBlock.TOP_SHAFT),
                () -> "生存扳手点 encased 齿轮应无效且方块原样（useOn 返回 " + result + "）");
        helper.succeed();
    }

    private static InteractionResult wrench(GameTestHelper helper, BlockPos pos, boolean sneaking) {
        return wrench(helper, pos, sneaking, GameType.SURVIVAL, Direction.UP);
    }

    private static InteractionResult wrench(GameTestHelper helper, BlockPos pos, boolean sneaking,
            GameType gameType, Direction face) {
        Player player = helper.makeMockPlayer(gameType);
        ItemStack wrench = AllItems.WRENCH.get().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        player.setShiftKeyDown(sneaking);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), face, absolute, true);
        return wrench.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private CreativeKineticsGameTest() {
    }
}

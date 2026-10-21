package dimblend.blocks.gametest;

import com.simibubi.create.AllItems;
import dimblend.blocks.api.CreativeCopycatHiding;
import dimblend.blocks.compat.copycats.CreativeCopycats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Supplier;

/**
 * C5 伪装板/半砖隐藏手势的服务端 GameTest（2026-10-22 拍板：隐藏/恢复放开生存，
 * 原为仅创造玩家）。直接驱动 {@code BlockState#useWithoutItem} 与
 * {@code WrenchItem#useOn} → IWrenchable 派发链（与服务端交互管线同一条）。
 *
 * <p>不加 {@code @GameTestHolder}：类内硬引用 {@link CreativeCopycats}（Copycats+ 类型），
 * 自动扫描注册会在 copycats 缺席时崩类加载；改由 {@code DimBlendBlocks} 在
 * {@code RegisterGameTestsEvent} 内经 isLoaded("copycats") 守卫注册（dev 下
 * {@code ./gradlew :dimblend-blocks:runGameTestServer -PwithCopycats}）。</p>
 */
@PrefixGameTestTemplate(false)
public final class CreativeCopycatHidingGameTest {

    /** 生存空手右键未贴材质的创造伪装板 → 隐藏（2026-10-22 放开生存的核心用例）。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void survivalBareHandHidesPanel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_PANEL.get().defaultBlockState());
        InteractionResult result = bareHand(helper, pos, GameType.SURVIVAL);
        assertHidden(helper, pos, true,
                () -> "生存空手右键未贴材质创造板应隐藏（useWithoutItem 返回 " + result + "）");
        helper.succeed();
    }

    /** 创造空手右键仍可隐藏（回归：放开生存不影响创造路径）。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void creativeBareHandHidesPanel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_PANEL.get().defaultBlockState());
        InteractionResult result = bareHand(helper, pos, GameType.CREATIVE);
        assertHidden(helper, pos, true,
                () -> "创造空手右键未贴材质创造板应隐藏（useWithoutItem 返回 " + result + "）");
        helper.succeed();
    }

    /** 主手持有物品（扳手）时不命中空手手势，不隐藏。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void survivalHoldingWrenchDoesNotHidePanel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_PANEL.get().defaultBlockState());
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, AllItems.WRENCH.get().getDefaultInstance());
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, true);
        helper.getBlockState(pos).useWithoutItem(helper.getLevel(), player, hit);
        assertHidden(helper, pos, false, () -> "主手持扳手（非空手）不应触发隐藏");
        helper.succeed();
    }

    /** 生存扳手右键隐藏板 → 恢复正常。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void survivalWrenchRestoresHiddenPanel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_PANEL.get().defaultBlockState());
        CreativeCopycatHiding.setHidden(helper.getLevel(), helper.absolutePos(pos), true);
        assertHidden(helper, pos, true, () -> "前置隐藏失败");
        InteractionResult result = wrench(helper, pos);
        assertHidden(helper, pos, false,
                () -> "生存扳手右键隐藏板应恢复正常（useOn 返回 " + result + "）");
        helper.succeed();
    }

    /** 生存空手右键未贴材质的创造伪装半砖 → 隐藏。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void survivalBareHandHidesSlab(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_SLAB.get().defaultBlockState());
        InteractionResult result = bareHand(helper, pos, GameType.SURVIVAL);
        assertHidden(helper, pos, true,
                () -> "生存空手右键未贴材质创造半砖应隐藏（useWithoutItem 返回 " + result + "）");
        helper.succeed();
    }

    /** 生存扳手右键隐藏半砖 → 恢复正常。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void survivalWrenchRestoresHiddenSlab(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, CreativeCopycats.CREATIVE_COPYCAT_SLAB.get().defaultBlockState());
        CreativeCopycatHiding.setHidden(helper.getLevel(), helper.absolutePos(pos), true);
        assertHidden(helper, pos, true, () -> "前置隐藏失败");
        InteractionResult result = wrench(helper, pos);
        assertHidden(helper, pos, false,
                () -> "生存扳手右键隐藏半砖应恢复正常（useOn 返回 " + result + "）");
        helper.succeed();
    }

    private static InteractionResult bareHand(GameTestHelper helper, BlockPos pos, GameType gameType) {
        Player player = helper.makeMockPlayer(gameType);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, true);
        return helper.getBlockState(pos).useWithoutItem(helper.getLevel(), player, hit);
    }

    private static InteractionResult wrench(GameTestHelper helper, BlockPos pos) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack wrench = AllItems.WRENCH.get().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        BlockPos absolute = helper.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, true);
        return wrench.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static void assertHidden(GameTestHelper helper, BlockPos pos, boolean expected,
            Supplier<String> message) {
        boolean hidden = CreativeCopycatHiding.isHidden(helper.getLevel(), helper.absolutePos(pos));
        if (hidden != expected) {
            throw new GameTestAssertException(message.get());
        }
    }

    private CreativeCopycatHidingGameTest() {
    }
}

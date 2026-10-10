package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainGrowthBoost;
import dimblend.experience.compat.create.ItemDrainHud;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 分液池 HUD 同步标签（{@code ItemDrainHudSyncMixin} 写入的
 * {@link ItemDrainHud#TAG_NEXT_BOOST_AT}）的运行时织入检查：护目镜倒计时全靠
 * 该键，回归（键缺失/值域错误）客户端只能看到“无水状态变化”，无任何显式失败，
 * 纯函数单测覆盖不到 write 注入，故做运行时验证。
 *
 * <p>上弦/击发由 10-tick 节拍驱动（gameTime%10），15 tick 等待保证至少过一拍。
 * 周期配 1 秒（下限），上弦值 = 上弦拍 gameTime + 20；断言值域 (now, now+20]
 * 而非定点值，规避断言拍与上弦拍的相对位置不确定。开关关闭时 countdown 被
 * {@link ItemDrainGrowthBoost#reset} 清零，写键恒为 -1（未在计时）。</p>
 *
 * <p>同批次用例并发执行（vanilla GameTest 语义），两条用例都翻转共享全局配置，
 * 必须各放独立批次串行（同 {@code pipe_refill_toggle} 先例）；各用例开头显式 set
 * 期望值、收尾还原。模板复用 {@code item_drain_refill} 空平台（本测试无需水源与作物）。
 * 仅 Create 在场时注册（见 {@code DimBlend#onRegisterGameTests} 的 ModList 守卫）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class ItemDrainHudSyncGameTests {

    private ItemDrainHudSyncGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "item_drain_hud_armed")
    public static void writeContainsNextBoostAtWhenArmed(GameTestHelper helper) {
        Config.ITEM_DRAIN_GROWTH.set(true);
        Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(1);
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, AllBlocks.ITEM_DRAIN.get().defaultBlockState());

        helper.runAfterDelay(15, () -> {
            BlockEntity be = helper.getBlockEntity(rel);
            helper.assertTrue(be instanceof ItemDrainBlockEntity,
                    "item drain block entity missing");
            int countdown = ((ItemDrainGrowthBoost.HasState) be).dimblend$growthState().countdown;
            helper.assertTrue(countdown > 0, "growth countdown must be armed after a beat, got " + countdown);

            CompoundTag tag = new CompoundTag();
            ((ItemDrainBlockEntity) be).write(tag, helper.getLevel().registryAccess(), true);
            helper.assertTrue(tag.contains(ItemDrainHud.TAG_NEXT_BOOST_AT),
                    "client packet must carry " + ItemDrainHud.TAG_NEXT_BOOST_AT);
            long nextBoostAt = tag.getLong(ItemDrainHud.TAG_NEXT_BOOST_AT);
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(nextBoostAt > now && nextBoostAt <= now + 20,
                    "next boost at must be within (now, now+20], got delta " + (nextBoostAt - now));

            Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(30); // 还原默认，防影响后续批次/复跑
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "item_drain_hud_disabled")
    public static void writeContainsMinusOneWhenDisabled(GameTestHelper helper) {
        Config.ITEM_DRAIN_GROWTH.set(false);
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, AllBlocks.ITEM_DRAIN.get().defaultBlockState());

        helper.runAfterDelay(15, () -> {
            BlockEntity be = helper.getBlockEntity(rel);
            helper.assertTrue(be instanceof ItemDrainBlockEntity,
                    "item drain block entity missing");

            CompoundTag tag = new CompoundTag();
            ((ItemDrainBlockEntity) be).write(tag, helper.getLevel().registryAccess(), true);
            helper.assertTrue(tag.contains(ItemDrainHud.TAG_NEXT_BOOST_AT),
                    "client packet must carry " + ItemDrainHud.TAG_NEXT_BOOST_AT + " even when disabled");
            helper.assertTrue(tag.getLong(ItemDrainHud.TAG_NEXT_BOOST_AT) == -1,
                    "next boost at must be -1 when growth disabled, got "
                            + tag.getLong(ItemDrainHud.TAG_NEXT_BOOST_AT));

            Config.ITEM_DRAIN_GROWTH.set(true); // 还原默认，防影响后续批次/复跑
            helper.succeed();
        });
    }
}

package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.compat.create.ItemDrainGrowthBoost;
import dimblend.experience.compat.create.ItemDrainHud;
import dimblend.experience.compat.create.ItemDrainPipeRefill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 分液池催熟「击发点之后」路径的运行时覆盖（{@link ItemDrainHudSyncGameTests} 只到
 * 上弦/卸弦状态机为止，capability 注水→扣水→作物 age 增长此前零覆盖，见 2026-10-22
 * 新存档“倒计时走但不催不扣”排查）：注 1000mb 水 + 1 秒周期 + 一株小麦，击发后断言
 * 作物 age 增长且水箱恰扣 10mb；带数据组件的存水（供水链路差异会附加组件，裸探头
 * 匹配失配致 "cannot drain" 空走，2026-10-23 实机事故）同样击发扣水；另锁定候选口径——
 * 草方块不算候选（户外农田恒有草，算候选则“无作物停走”永不触发且击发抽中草白扣水）。
 *
 * <p>节拍时序：击发 = 上弦（首个节拍）后 2 节拍 ≈ 20 tick，最晚 30 tick；35 tick 处
 * 断言恰好接住唯一一次击发（第二次击发最早 41 tick）。配置翻转约定同
 * {@code ItemDrainHudSyncGameTests}：每用例独立批次串行，开头设值、收尾还原。
 * 模板复用 {@code item_drain_refill} 空平台。仅 Create 在场时注册（ModList 守卫）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class ItemDrainGrowthFireGameTests {

    private ItemDrainGrowthFireGameTests() {
    }

    private static final BlockPos DRAIN = new BlockPos(1, 1, 1);
    private static final BlockPos FARMLAND = new BlockPos(2, 1, 1);
    private static final BlockPos CROP = new BlockPos(2, 2, 1);

    private static void fillWater(GameTestHelper helper, int mb) {
        // Create 原生分液池水箱 forbidInsertion，须先置回允许注水（生产中由
        // ItemDrainPipeRefillMixin 逐 tick 置位；同拍填充等不到首拍，直接调公开口）
        ItemDrainPipeRefill.openInsertion((ItemDrainBlockEntity) helper.getBlockEntity(DRAIN));
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(DRAIN), Direction.DOWN);
        helper.assertTrue(handler != null && handler.getTanks() >= 1,
                "item drain must expose a DOWN fluid capability");
        int filled = handler.fill(water(mb), IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(filled == mb, "tank must accept " + mb + " mb water, filled " + filled);
    }

    private static FluidStack water(int mb) {
        return new FluidStack(Fluids.WATER, mb);
    }

    private static int storedWater(GameTestHelper helper) {
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(DRAIN), Direction.DOWN);
        helper.assertTrue(handler != null && handler.getTanks() >= 1,
                "item drain must expose a DOWN fluid capability");
        return handler.getFluidInTank(0).getAmount();
    }

    private static long nextBoostAtOf(GameTestHelper helper) {
        BlockEntity be = helper.getBlockEntity(DRAIN);
        CompoundTag tag = new CompoundTag();
        ((ItemDrainBlockEntity) be).write(tag, helper.getLevel().registryAccess(), true);
        return tag.getLong(ItemDrainHud.TAG_NEXT_BOOST_AT);
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "item_drain_fire_grow")
    public static void fireGrowsCropAndDrainsWater(GameTestHelper helper) {
        Config.ITEM_DRAIN_GROWTH.set(true);
        Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(1);
        Config.ITEM_DRAIN_GROWTH_COST_MB.set(10);
        helper.setBlock(DRAIN, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
        helper.setBlock(FARMLAND, Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(CROP, Blocks.WHEAT.defaultBlockState());
        fillWater(helper, 1000);

        helper.runAfterDelay(35, () -> {
            int age = helper.getBlockState(CROP).getValue(CropBlock.AGE);
            helper.assertTrue(age > 0, "wheat age must grow after the boost fires, got " + age);
            int stored = storedWater(helper);
            helper.assertTrue(stored == 990, "one fire must drain exactly 10 mb, stored " + stored);

            Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(30); // 还原默认，防影响后续批次/复跑
            helper.succeed();
        });
    }

    /**
     * 回归（2026-10-23 实机 "cannot drain 10 mb" 事故）：存水带数据组件时（供水链路
     * 差异会给水栈附加组件），裸 {@code FluidStack(WATER)} 探头在
     * {@code FluidTank.drain} 的类型+组件匹配下失配，倒计时空走、不催不扣；探头
     * 必须派生自水箱实存 stack。
     */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "item_drain_fire_components")
    public static void fireDrainsComponentCarryingWater(GameTestHelper helper) {
        Config.ITEM_DRAIN_GROWTH.set(true);
        Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(1);
        Config.ITEM_DRAIN_GROWTH_COST_MB.set(10);
        helper.setBlock(DRAIN, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
        helper.setBlock(FARMLAND, Blocks.FARMLAND.defaultBlockState());
        helper.setBlock(CROP, Blocks.WHEAT.defaultBlockState());
        ItemDrainPipeRefill.openInsertion((ItemDrainBlockEntity) helper.getBlockEntity(DRAIN));
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(DRAIN), Direction.DOWN);
        helper.assertTrue(handler != null && handler.getTanks() >= 1,
                "item drain must expose a DOWN fluid capability");
        FluidStack stack = water(1000);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("well water"));
        int filled = handler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(filled == 1000, "tank must accept 1000 mb component water, filled " + filled);

        helper.runAfterDelay(35, () -> {
            int age = helper.getBlockState(CROP).getValue(CropBlock.AGE);
            helper.assertTrue(age > 0, "wheat age must grow with component-carrying water, got " + age);
            int stored = storedWater(helper);
            helper.assertTrue(stored == 990, "one fire must drain exactly 10 mb, stored " + stored);

            Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(30); // 还原默认，防影响后续批次/复跑
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience",
            timeoutTicks = 200, batch = "item_drain_grass_no_candidate")
    public static void grassIsNotACandidate(GameTestHelper helper) {
        Config.ITEM_DRAIN_GROWTH.set(true);
        Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(1);
        helper.setBlock(DRAIN, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
        // 草方块（上方空气，原版骨粉有效目标）不算催熟候选：倒计时保持停走
        helper.setBlock(FARMLAND, Blocks.GRASS_BLOCK.defaultBlockState());

        helper.runAfterDelay(25, () -> {
            BlockEntity be = helper.getBlockEntity(DRAIN);
            int countdown = ((ItemDrainGrowthBoost.HasState) be).dimblend$growthState().countdown;
            helper.assertTrue(countdown == 0,
                    "growth countdown must stay disarmed with only grass around, got " + countdown);
            helper.assertTrue(nextBoostAtOf(helper) == -1,
                    "next boost at must be -1 with only grass around");

            Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.set(30); // 还原默认，防影响后续批次/复跑
            helper.succeed();
        });
    }
}

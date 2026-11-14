package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import dimblend.experience.compat.create.vault.VaultDisplaySources;
import dimblend.experience.compat.create.vault.VaultInventoryReader;
import dimblend.experience.compat.create.vault.VaultStorageMath;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 显示连接器读取物品保险库的运行时覆盖：方块实体类型关联 + 从非主控段读整台多结构。
 *
 * <p>JUnit 侧只能覆盖 {@link VaultStorageMath} 的口径；这里补三件单测抓不到的事：
 * {@code DisplaySource.BY_BLOCK_ENTITY} 真的挂上了 {@code create:item_vault}、
 * Create 的整台拼接库存真的能从<b>非主控段</b>读到远端段的物品、
 * 以及计位/计件两种口径在同一份实际库存上的数值。</p>
 *
 * <p>模板复用 3×3×3 的空平台；两台物品保险库沿 X 相邻拼成 1×1×2 多结构
 * （{@code ItemVaultBlock.HORIZONTAL_AXIS = X}，Create 只允许水平拼接）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class VaultDisplaySourceGameTests {

    private static final BlockPos FIRST = new BlockPos(1, 1, 1);
    private static final BlockPos SECOND = new BlockPos(2, 1, 1);

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void attachedSegmentReadsWholeMultiblock(GameTestHelper helper) {
        BlockState vaultState = AllBlocks.ITEM_VAULT.getDefaultState()
                .setValue(ItemVaultBlock.HORIZONTAL_AXIS, Direction.Axis.X);
        helper.setBlock(FIRST, vaultState);
        helper.setBlock(SECOND, vaultState);

        BlockEntity firstBe = helper.getBlockEntity(FIRST);
        BlockEntity secondBe = helper.getBlockEntity(SECOND);
        helper.assertTrue(firstBe instanceof ItemVaultBlockEntity, "第一台必须是物品保险库");
        helper.assertTrue(secondBe instanceof ItemVaultBlockEntity, "第二台必须是物品保险库");
        ItemVaultBlockEntity controller = (ItemVaultBlockEntity) firstBe;
        ItemVaultBlockEntity attached = (ItemVaultBlockEntity) secondBe;
        helper.assertTrue(controller.isController(), "相邻两台物品保险库必须拼成一台多结构，主控在 " + FIRST);
        helper.assertTrue(!attached.isController(), "贴在 " + SECOND + " 的那台必须是非主控段");

        int perBlock = controller.getInventoryOfBlock().getSlots();
        helper.assertTrue(perBlock >= 3, "保险库每块槽位数必须 ≥3（create 默认 vaultCapacity=20），实际 " + perBlock);

        // 三种堆叠口径各塞一格：64 堆叠 5 个、16 堆叠 16 个、不可堆叠 1 个
        attached.getInventoryOfBlock().insertItem(0, new ItemStack(Items.STONE, 5), false);
        attached.getInventoryOfBlock().insertItem(1, new ItemStack(Items.DIAMOND_SWORD, 1), false);
        attached.getInventoryOfBlock().insertItem(2, new ItemStack(Items.EGG, 16), false);

        VaultStorageMath.Tally fromSegment = VaultInventoryReader.tally(helper.getLevel(), helper.absolutePos(SECOND));
        helper.assertTrue(fromSegment != null, "非主控段必须能读出整台保险库（capability 为空即回归）");
        helper.assertTrue(fromSegment.totalSlots() == 2 * perBlock,
                "总量必须覆盖整台多结构：" + fromSegment.totalSlots() + " != 2 × " + perBlock);
        helper.assertTrue(fromSegment.usedGroups() == 3,
                "计位数量：三格有物品，含只放 1 个的可堆叠物与不可堆叠物 → " + fromSegment.usedGroups());
        helper.assertTrue(fromSegment.usedPieces() == 5L + 64L + 64L,
                "计件数量：5 石(1/件) + 1 钻剑(64/件) + 16 蛋(4/件) → " + fromSegment.usedPieces());
        helper.assertTrue(fromSegment.totalPieces() == 2L * perBlock * VaultStorageMath.GROUP_PIECES,
                "计件总量 = 总格数 × 64 → " + fromSegment.totalPieces());

        VaultStorageMath.Tally fromController = VaultInventoryReader.tally(helper.getLevel(), controller);
        helper.assertTrue(fromController != null, "主控段同样必须能读出整台保险库");
        helper.assertTrue(fromController.totalSlots() == fromSegment.totalSlots()
                        && fromController.usedGroups() == fromSegment.usedGroups()
                        && fromController.usedPieces() == fromSegment.usedPieces(),
                "主控段与非主控段必须读到同一份整台库存");

        List<DisplaySource> sources = DisplaySource.getAll(helper.getLevel(), helper.absolutePos(FIRST));
        helper.assertTrue(sources.contains(VaultDisplaySources.STORAGE_GROUPS.get()),
                "create:item_vault 必须关联「读取存量-计位」来源");
        helper.assertTrue(sources.contains(VaultDisplaySources.STORAGE_ITEMS.get()),
                "create:item_vault 必须关联「读取存量-计件」来源");
        helper.succeed();
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void emptyVaultReportsZeroUsedGroups(GameTestHelper helper) {
        BlockState vaultState = AllBlocks.ITEM_VAULT.getDefaultState()
                .setValue(ItemVaultBlock.HORIZONTAL_AXIS, Direction.Axis.Z);
        helper.setBlock(FIRST, vaultState);

        VaultStorageMath.Tally tally = VaultInventoryReader.tally(helper.getLevel(), helper.getBlockEntity(FIRST));
        helper.assertTrue(tally != null, "单台保险库必须可读");
        helper.assertTrue(tally.usedGroups() == 0 && tally.usedPieces() == 0L,
                "空保险库的计位/计件数量都必须是 0");
        helper.assertTrue(tally.totalPieces() == tally.totalSlots() * VaultStorageMath.GROUP_PIECES,
                "计件总量必须是总格数 × 64");
        helper.succeed();
    }

    private VaultDisplaySourceGameTests() {
    }
}

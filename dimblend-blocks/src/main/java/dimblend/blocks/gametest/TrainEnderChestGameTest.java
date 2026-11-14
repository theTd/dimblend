package dimblend.blocks.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;

import codechicken.enderstorage.api.Frequency;
import codechicken.enderstorage.init.EnderStorageModContent;
import codechicken.enderstorage.tile.TileEnderChest;
import codechicken.lib.colour.EnumColour;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dimblend.blocks.compat.enderstorage.TrainEnderChestBlockEntity;
import dimblend.blocks.compat.enderstorage.TrainEnderChestGuard;
import dimblend.blocks.compat.enderstorage.TrainEnderChests;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 列车末影物流箱（{@code dimblend_blocks:train_ender_chest}）服务端端到端 GameTest：
 * 覆盖 Sable 子层级放置校验（拒绝地面 + 放行真实 Sable 子层级 + 事件层兜底拆除）、
 * 同频互通与异频/原末影箱通道隔离、顶部三垫片染色交互、比较器信号与带频率掉落物、
 * 以及合成配方与染色配方校验。
 *
 * <p>用法：{@code ./gradlew :dimblend-blocks:runGameTestServer -PwithEnderStorage}</p>
 */
@PrefixGameTestTemplate(false)
public final class TrainEnderChestGameTest {

    /** 地面（非 Sable 结构）在生存与创造模式下均拒绝放置，且不吃物品、不洗白频率；事件兜底同样拆除。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void rejectsPlacementOffSableAndPreservesStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floorRel = new BlockPos(1, 1, 1);
        BlockPos floorAbs = helper.absolutePos(floorRel);
        level.setBlockAndUpdate(floorAbs, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(floorAbs.above(), Blocks.AIR.defaultBlockState());

        Frequency customFreq = new Frequency(EnumColour.RED, EnumColour.GREEN, EnumColour.BLUE);
        for (GameType mode : List.of(GameType.SURVIVAL, GameType.CREATIVE)) {
            Player player = helper.makeMockPlayer(mode);
            ItemStack stack = new ItemStack(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM.get(), 1);
            customFreq.writeToStack(stack);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atBottomCenterOf(floorAbs).add(0.0, 1.0, 0.0), Direction.UP, floorAbs, false);
            InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            if (result.consumesAction() || !level.getBlockState(floorAbs.above()).isAir()) {
                throw new GameTestAssertException("模式 " + mode + " 下非 Sable 地面不应允许放置列车末影物流箱，结果=" + result);
            }
            if (stack.getCount() != 1 || !customFreq.equals(Frequency.readFromStack(stack))) {
                throw new GameTestAssertException("拒绝放置后手中物品数量或三色频率被破坏：" + stack);
            }
        }

        // 纵深防御：模拟绕过 BlockItem#canPlace 的 EntityPlaceEvent，确认 tick 末拆除。
        BlockPos targetAbs = floorAbs.above();
        BlockSnapshot snapshot = BlockSnapshot.create(level.dimension(), level, targetAbs);
        level.setBlockAndUpdate(targetAbs, TrainEnderChests.TRAIN_ENDER_CHEST.get().defaultBlockState());
        BlockEvent.EntityPlaceEvent placeEvent = new BlockEvent.EntityPlaceEvent(
                snapshot, Blocks.STONE.defaultBlockState(), null);
        NeoForge.EVENT_BUS.post(placeEvent);
        TrainEnderChestGuard.onServerTickPost(new ServerTickEvent.Post(() -> true, level.getServer()));
        if (!level.getBlockState(targetAbs).isAir()) {
            throw new GameTestAssertException("TrainEnderChestGuard 应在 tick 末拆除非 Sable 上的绕过放置");
        }
        helper.succeed();
    }

    /** 在真实组装出的 Sable 子层级 plot 内使用物品右键放置成功，并正确写入三色频率。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void allowsPlacementInsideRealSableSubLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchorAbs = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(anchorAbs, Blocks.STONE.defaultBlockState());

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            throw new GameTestAssertException("Sable ServerSubLevelContainer 未初始化");
        }
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(
                level, anchorAbs, List.of(anchorAbs), new BoundingBox3i(new BoundingBox(anchorAbs)));
        subLevel.updateBoundingBox();
        subLevel.forceUpdateGlobalBounds();
        try {
            BlockPos plotStone = subLevel.getPlot().getCenterBlock();
            BlockPos plotTarget = plotStone.above();
            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            Frequency expectedFreq = new Frequency(EnumColour.MAGENTA, EnumColour.WHITE, EnumColour.LIME);
            ItemStack stack = new ItemStack(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM.get(), 1);
            expectedFreq.writeToStack(stack);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);

            BlockHitResult hit = new BlockHitResult(
                    Vec3.atBottomCenterOf(plotStone).add(0.0, 1.0, 0.0), Direction.UP, plotStone, false);
            InteractionResult result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            if (!result.consumesAction()
                    || !level.getBlockState(plotTarget).is(TrainEnderChests.TRAIN_ENDER_CHEST.get())) {
                throw new GameTestAssertException("在 Sable 子层级上应允许放置列车末影物流箱，实际="
                        + result + " / " + level.getBlockState(plotTarget));
            }
            if (!(level.getBlockEntity(plotTarget) instanceof TrainEnderChestBlockEntity be)
                    || !expectedFreq.equals(be.getFrequency())) {
                throw new GameTestAssertException("Sable 子层级放置后 BE 频率未同步写入");
            }
        } finally {
            if (!subLevel.isRemoved()) {
                container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
            }
        }
        helper.succeed();
    }

    /** 同频箱体经 IItemHandler 互通、比较器信号与掉落保留频率，且与异频及普通末影箱严格隔离。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void sameFrequencySharesItemsWhileIsolatedFromOtherFrequenciesAndVanillaEnderStorage(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos posA = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos posB = helper.absolutePos(new BlockPos(2, 1, 0));
        BlockPos posRegularEnder = helper.absolutePos(new BlockPos(1, 1, 2));

        level.setBlockAndUpdate(posA, TrainEnderChests.TRAIN_ENDER_CHEST.get().defaultBlockState());
        level.setBlockAndUpdate(posB, TrainEnderChests.TRAIN_ENDER_CHEST.get().defaultBlockState());
        level.setBlockAndUpdate(posRegularEnder, EnderStorageModContent.ENDER_CHEST_BLOCK.get().defaultBlockState());

        TrainEnderChestBlockEntity beA = (TrainEnderChestBlockEntity) level.getBlockEntity(posA);
        TrainEnderChestBlockEntity beB = (TrainEnderChestBlockEntity) level.getBlockEntity(posB);
        TileEnderChest beRegular = (TileEnderChest) level.getBlockEntity(posRegularEnder);
        if (beA == null || beB == null || beRegular == null) {
            throw new GameTestAssertException("方块实体创建失败");
        }

        Frequency sharedFreq = new Frequency(EnumColour.ORANGE, EnumColour.CYAN, EnumColour.PURPLE);
        beA.setFreq(sharedFreq);
        beB.setFreq(sharedFreq);
        beRegular.setFreq(sharedFreq);
        beA.getStorage().clearStorage();
        beRegular.getStorage().clearStorage();

        IItemHandler capA = level.getCapability(Capabilities.ItemHandler.BLOCK, posA, Direction.UP);
        IItemHandler capB = level.getCapability(Capabilities.ItemHandler.BLOCK, posB, Direction.DOWN);
        IItemHandler capRegular = level.getCapability(Capabilities.ItemHandler.BLOCK, posRegularEnder, Direction.UP);
        if (capA == null || capB == null || capRegular == null) {
            throw new GameTestAssertException("Capabilities.ItemHandler.BLOCK 未正确注册");
        }

        ItemStack remainder = capA.insertItem(0, new ItemStack(Items.DIAMOND, 12), false);
        if (!remainder.isEmpty()) {
            throw new GameTestAssertException("向列车箱 A 插入物品失败，剩余=" + remainder);
        }
        ItemStack extracted = capB.extractItem(0, 5, false);
        if (!extracted.is(Items.DIAMOND) || extracted.getCount() != 5) {
            throw new GameTestAssertException("从同频列车箱 B 提取应得 5 颗钻石，实际=" + extracted);
        }
        if (capA.getStackInSlot(0).getCount() != 7) {
            throw new GameTestAssertException("提取后列车箱 A 第 0 槽应剩 7 颗钻石，实际=" + capA.getStackInSlot(0));
        }
        if (!capRegular.getStackInSlot(0).isEmpty()) {
            throw new GameTestAssertException("同三色频率的普通 enderstorage:ender_chest 不应与列车末影物流箱串频");
        }
        if (level.getBlockState(posA).getAnalogOutputSignal(level, posA) <= 0) {
            throw new GameTestAssertException("非空列车末影物流箱应输出大于 0 的红石比较器信号");
        }

        // 改频后 B 立即切到新通道（为空），A 仍保留 7 颗钻石。
        Frequency otherFreq = new Frequency(EnumColour.YELLOW, EnumColour.YELLOW, EnumColour.YELLOW);
        beB.setFreq(otherFreq);
        beB.getStorage().clearStorage();
        IItemHandler capBAfter = level.getCapability(Capabilities.ItemHandler.BLOCK, posB, Direction.DOWN);
        if (capBAfter == null || !capBAfter.getStackInSlot(0).isEmpty() || capA.getStackInSlot(0).getCount() != 7) {
            throw new GameTestAssertException("改频后 B 应为空且不影响原频率 A");
        }

        // 挖掘掉落保留三色频率。
        List<ItemStack> drops = Block.getDrops(level.getBlockState(posA), level, posA, beA);
        if (drops.size() != 1
                || !drops.get(0).is(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM.get())
                || !sharedFreq.equals(Frequency.readFromStack(drops.get(0)))) {
            throw new GameTestAssertException("挖掘掉落应保留三色频率，实际=" + drops);
        }

        beA.getStorage().clearStorage();
        helper.succeed();
    }

    /** 玩家手持染料对准顶部三枚垫片（subHit 1..3）右键可逐格独立染色。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void inWorldTopPadsCanBeDyedIndividually(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos chestAbs = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(chestAbs.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(chestAbs.above(2), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(chestAbs, TrainEnderChests.TRAIN_ENDER_CHEST.get().defaultBlockState());

        TrainEnderChestBlockEntity be = (TrainEnderChestBlockEntity) level.getBlockEntity(chestAbs);
        if (be == null) {
            throw new GameTestAssertException("TrainEnderChestBlockEntity 不存在");
        }
        be.rotation = 0;

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        double[] buttonXs = new double[]{0.3125, 0.5, 0.6875};
        Item[] dyes = new Item[]{Items.RED_DYE, Items.LIME_DYE, Items.BLUE_DYE};
        for (int i = 0; i < 3; i++) {
            ItemStack dyeStack = new ItemStack(dyes[i], 2);
            player.getInventory().selected = 0;
            player.getInventory().setItem(0, dyeStack);
            player.setPos(chestAbs.getX() + buttonXs[i], chestAbs.getY() + 1.2D, chestAbs.getZ() + 0.5D);
            player.setYRot(0.0F);
            player.setXRot(90.0F);
            BlockHitResult clientHit = new BlockHitResult(
                    new Vec3(chestAbs.getX() + buttonXs[i], chestAbs.getY() + 0.9375D, chestAbs.getZ() + 0.5D),
                    Direction.UP, chestAbs, false);
            level.getBlockState(chestAbs).useItemOn(dyeStack, level, player, InteractionHand.MAIN_HAND, clientHit);
            if (dyeStack.getCount() != 1) {
                throw new GameTestAssertException("第 " + (i + 1) + " 枚垫片染色应消耗 1 个染料，剩余=" + dyeStack.getCount());
            }
        }

        Frequency expected = new Frequency(EnumColour.RED, EnumColour.LIME, EnumColour.BLUE);
        if (!expected.equals(be.getFrequency())) {
            throw new GameTestAssertException("三枚垫片分别染色后频率应为 RED/LIME/BLUE，实际=" + be.getFrequency());
        }
        helper.succeed();
    }

    /** 合成台配方、染色配方与物品悬浮描述均符合需求规格。 */
    @GameTest(template = "empty_3x3", templateNamespace = "dimblend_blocks")
    public static void craftingRecipeRecolourRecipeAndTooltipMatchSpec(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // 1. 标准 3x3 合成：烈焰粉-白色羊毛-烈焰粉 / 铁板-物品保险库-铁板 / 烈焰粉-末影珍珠-烈焰粉
        CraftingInput craftGrid = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.BLAZE_POWDER), new ItemStack(Items.WHITE_WOOL), new ItemStack(Items.BLAZE_POWDER),
                AllItems.IRON_SHEET.asStack(), AllBlocks.ITEM_VAULT.asStack(), AllItems.IRON_SHEET.asStack(),
                new ItemStack(Items.BLAZE_POWDER), new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.BLAZE_POWDER)));
        Optional<RecipeHolder<CraftingRecipe>> matchedCraft =
                level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftGrid, level);
        if (matchedCraft.isEmpty()) {
            throw new GameTestAssertException("标准 3x3 配方未能匹配列车末影物流箱");
        }
        ItemStack crafted = matchedCraft.get().value().assemble(craftGrid, level.registryAccess());
        if (!crafted.is(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM.get())
                || crafted.getCount() != 1
                || !new Frequency(EnumColour.WHITE, EnumColour.WHITE, EnumColour.WHITE)
                        .equals(Frequency.readFromStack(crafted))) {
            throw new GameTestAssertException("标准合成产物或初始三白频率不符合预期：" + crafted);
        }

        // 2. 合成台染色配方（enderstorage:recolour_recipe）：上方左右放红/蓝染料，正中放箱子 -> 红/白/蓝
        CraftingInput recolourGrid = CraftingInput.of(3, 3, List.of(
                new ItemStack(Items.RED_DYE), ItemStack.EMPTY, new ItemStack(Items.BLUE_DYE),
                ItemStack.EMPTY, crafted.copy(), ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY));
        Optional<RecipeHolder<CraftingRecipe>> matchedRecolour =
                level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, recolourGrid, level);
        if (matchedRecolour.isEmpty()) {
            throw new GameTestAssertException("recolour_train_ender_chest 染色配方未匹配");
        }
        ItemStack recoloured = matchedRecolour.get().value().assemble(recolourGrid, level.registryAccess());
        Frequency expectedRecolour = new Frequency(EnumColour.RED, EnumColour.WHITE, EnumColour.BLUE);
        if (!recoloured.is(TrainEnderChests.TRAIN_ENDER_CHEST_ITEM.get())
                || !expectedRecolour.equals(Frequency.readFromStack(recoloured))) {
            throw new GameTestAssertException("合成台染色后频率应为 RED/WHITE/BLUE，实际="
                    + Frequency.readFromStack(recoloured));
        }

        // 3. 悬浮提示含「仅可在列车上使用」翻译键 + 三色频率行。
        List<Component> tooltip = new ArrayList<>();
        recoloured.getItem().appendHoverText(
                recoloured, Item.TooltipContext.of(level), tooltip, TooltipFlag.NORMAL);
        boolean hasTrainOnlyTooltip = tooltip.stream()
                .map(Component::getContents)
                .anyMatch(c -> c instanceof TranslatableContents tc
                        && "block.dimblend_blocks.train_ender_chest.tooltip".equals(tc.getKey()));
        if (!hasTrainOnlyTooltip || tooltip.size() < 2) {
            throw new GameTestAssertException("物品悬浮提示缺少列车专属描述或三色频率：" + tooltip);
        }
        helper.succeed();
    }

    private TrainEnderChestGameTest() {
    }
}

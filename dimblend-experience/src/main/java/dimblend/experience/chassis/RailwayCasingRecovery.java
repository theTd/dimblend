package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/** Reclaims off-corridor railway parts without allowing wrench pickup to bypass drops. */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class RailwayCasingRecovery {
    public static final TagKey<Block> RECOVERABLE = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "railway_casing_recoverable"));
    private static final TagKey<Item> WRENCHES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private RailwayCasingRecovery() {}

    public static boolean applies(Level level, BlockPos pos, BlockState state) {
        if (!ModList.get().isLoaded("create") || !Config.isLoaded()
                || !RotatingDimension.is(level) || !state.is(RECOVERABLE)) return false;
        // Plot coordinates are storage addresses, not the vehicle's visible world position.
        return Math.abs((long) SableWorldPosition.projectBlock(level, pos).getZ()) > 32;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDrops(BlockDropsEvent event) {
        if (!applies(event.getLevel(), event.getPos(), event.getState())) return;
        Item blockItem = event.getState().getBlock().asItem();
        boolean replaced = false;
        var iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            var drop = iterator.next();
            if (!drop.getItem().is(blockItem)) continue;
            if (replaced) iterator.remove();
            else {
                drop.setItem(new ItemStack(AllBlocks.RAILWAY_CASING.asItem()));
                replaced = true;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWrench(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!event.getEntity().isShiftKeyDown()
                || !applies(event.getLevel(), event.getPos(), event.getLevel().getBlockState(event.getPos()))
                || (!(stack.getItem() instanceof WrenchItem) && !stack.is(WRENCHES))) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!event.getLevel().isClientSide()) event.getEntity().displayClientMessage(
                Component.translatable("message.dimblend_experience.chassis.casing_recovery"), true);
    }
}

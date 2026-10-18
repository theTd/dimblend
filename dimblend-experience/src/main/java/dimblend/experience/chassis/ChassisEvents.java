package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = DimBlend.MODID)
public final class ChassisEvents {
    private record Placement(ServerLevel level, BlockPos pos) {}
    private static final List<Placement> PENDING = new ArrayList<>();
    private ChassisEvents() {}

    public static boolean available() {
        var mods = ModList.get();
        return mods.isLoaded("dimblend") && mods.isLoaded("dimblend_blocks") && mods.isLoaded("create")
                && mods.isLoaded("copycats") && mods.isLoaded("simurail") && mods.isLoaded("sable") && mods.isLoaded("railways");
    }

    public static boolean inDimension(ServerLevel level) {
        return level.dimension().location().toString().equals("dimblend:rotating");
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!available() || !(event.getEntity() instanceof Player)
                || !(event.getLevel() instanceof ServerLevel level) || !inDimension(level)
                || !event.getBlockSnapshot().getCurrentState().is(AllBlocks.TRAIN_CONTROLS.get())) return;
        PENDING.add(new Placement(level, event.getPos().immutable()));
    }

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event) {
        if (!available() || !event.getLevel().getBlockState(event.getPos()).is(AllBlocks.TRAIN_CONTROLS.get())
                || !event.getLevel().dimension().location().toString().equals("dimblend:rotating")) return;
        // Preserve wrench removal before the irreversible confirmation.
        if (event.getItemStack().is(com.simibubi.create.AllItems.WRENCH)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ChassisSelection.open(player, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!available()) return;
        List<Placement> placements = List.copyOf(PENDING);
        PENDING.clear();
        for (Placement placement : placements) ChassisSelection.validatePlacement(placement.level(), placement.pos());
        ChassisSelection.tick(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (inDimension(level)) ChassisConstruction.tick(level);
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        PENDING.clear();
        ChassisSelection.clear();
    }
}

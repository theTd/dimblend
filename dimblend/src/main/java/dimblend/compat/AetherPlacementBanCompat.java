package dimblend.compat;

import com.aetherteam.aether.event.PlacementBanEvent;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Aether is a required dependency of dimblend; these are game-bus events, not cancellable events. */
@EventBusSubscriber(modid = "dimblend")
public final class AetherPlacementBanCompat {
    private AetherPlacementBanCompat() { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemCheck(PlacementBanEvent.CheckItem event) {
        if (shouldLiftBan(event.getLevel(), event.getPos())) event.setBanned(false);
    }

    // Also fired from RecipeHooks.checkExistenceBanned when an existing block notifies neighbors.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockCheck(PlacementBanEvent.CheckBlock event) {
        if (shouldLiftBan(event.getLevel(), event.getPos())) event.setBanned(false);
    }

    public static boolean shouldLiftBan(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof Level level) || !DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension())) {
            return false;
        }
        if (level instanceof ServerLevel server) {
            if (!(server.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating)) return false;
            BandLayout layout = rotating.layoutOrNull();
            if (layout == null) return false;
            return AetherPlacementBanRules.shouldLiftBan(true, pos.getX(), rotating.bandSize(),
                    region -> BandLayout.laneName(layout.delegate(region)));
        }
        // Item checks run on both sides. Query the event position, never the player's current band.
        // BlockBanRecipe matches pos.below() but emits CheckBlock at pos; both have the same band X.
        return level.isClientSide() && AetherPlacementBanRules.predictLiftBan(true,
                level.getBiome(pos).unwrapKey().map(key -> key.location().getNamespace()).orElse(""));
    }
}
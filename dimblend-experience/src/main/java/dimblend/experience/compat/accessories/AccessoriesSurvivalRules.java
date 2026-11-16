package dimblend.experience.compat.accessories;

import io.wispforest.owo.ui.layers.Layer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

/**
 * Survival inventory keeps the Accessories button hidden and unclickable; creative/adventure/spectator
 * keep the stock behavior. Client types are isolated in a nested holder so the common-side packet
 * guard never resolves client classes on a dedicated server.
 */
public final class AccessoriesSurvivalRules {

    public static boolean isSurvivalMode(@Nullable GameType mode) {
        return mode == GameType.SURVIVAL;
    }

    public static boolean isClientSurvival() {
        return ClientAccess.isSurvival();
    }

    public static Consumer<Layer<?, ?>.Instance> wrapLayerInitializer(
            Consumer<Layer<?, ?>.Instance> upstreamInitializer) {
        return ClientAccess.wrapLayerInitializer(upstreamInitializer);
    }

    private static final class ClientAccess {

        private static boolean isSurvival() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.gameMode != null && isSurvivalMode(minecraft.gameMode.getPlayerMode());
        }

        private static Consumer<Layer<?, ?>.Instance> wrapLayerInitializer(
                Consumer<Layer<?, ?>.Instance> upstreamInitializer) {
            return instance -> {
                if (instance.screen instanceof InventoryScreen && isSurvival()) {
                    return;
                }
                upstreamInitializer.accept(instance);
            };
        }

        private ClientAccess() {
        }
    }

    private AccessoriesSurvivalRules() {
    }
}

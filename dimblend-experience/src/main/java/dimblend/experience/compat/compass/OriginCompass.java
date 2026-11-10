package dimblend.experience.compat.compass;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Shared ordinary-compass target, without a compile dependency on Simulated. */
public final class OriginCompass {
    private static final ResourceLocation ROTATING = ResourceLocation.fromNamespaceAndPath("dimblend", "rotating");
    private static final ResourceLocation SUBLEVEL_TRACKER =
            ResourceLocation.fromNamespaceAndPath("simulated", "lodestone_compass_tracker");

    public static boolean isRotating(Level level) {
        return level.dimension().location().equals(ROTATING);
    }

    public static GlobalPos target(Level level) {
        return GlobalPos.of(level.dimension(), BlockPos.ZERO);
    }

    /** Called only on the navigation table's shared-spawn fallback branch. */
    public static BlockPos navigationSpawn(Level level, ItemStack stack, BlockPos original) {
        if (!isRotating(level) || !stack.is(Items.COMPASS) || stack.has(DataComponents.LODESTONE_TRACKER)) {
            return original;
        }
        DataComponentType<?> tracker = BuiltInRegistries.DATA_COMPONENT_TYPE.get(SUBLEVEL_TRACKER);
        // An unresolved sublevel binding is still a binding, not an ordinary compass.
        if (tracker != null && stack.has(tracker)) {
            return original;
        }
        return BlockPos.ZERO;
    }

    private OriginCompass() { }
}

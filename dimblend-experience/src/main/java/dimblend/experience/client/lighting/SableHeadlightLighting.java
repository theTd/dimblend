package dimblend.experience.client.lighting;

import dimblend.experience.Config;
import dimblend.experience.compat.dynlight.HeadlightMath;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;

/** Optional client integration; the shared tuning layer contains no renderer references. */
public final class SableHeadlightLighting {
    private SableHeadlightLighting() {}

    public static boolean matches(Level level, BlockPos pos) {
        var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return HeadlightMath.matches(id.getNamespace(), id.getPath());
    }

    public static boolean selected(Object source) {
        return source instanceof SableHeadlightSource headlight && headlight.dimblend$isHeadlight();
    }

    public static double radius() {
        return Config.isLoaded() ? Config.SABLE_HEADLIGHT_RADIUS.get() : 7.75D;
    }

    public static int brightness(int original) {
        if (!Config.isLoaded()) return original;
        return Config.SABLE_HEADLIGHT.get() ? Math.min(original, Config.SABLE_HEADLIGHT_BRIGHTNESS.get()) : 0;
    }

    public static double maxLight(BlockPos pos, DynamicLightSource source, double current) {
        if (!selected(source)) return SodiumDynamicLights.maxDynamicLightLevel(pos, source, current);
        double dx = pos.getX() + 0.5D - source.sdl$getDynamicLightX();
        double dy = pos.getY() + 0.5D - source.sdl$getDynamicLightY();
        double dz = pos.getZ() + 0.5D - source.sdl$getDynamicLightZ();
        return Math.max(current, HeadlightMath.light(dx * dx + dy * dy + dz * dz, radius(), source.sdl$getLuminance()));
    }

    public static void trackChunks(LevelRenderer renderer, Vec3 position,
                                   LongOpenHashSet previous, LongOpenHashSet next) {
        double radius = radius();
        BlockPos.MutableBlockPos section = new BlockPos.MutableBlockPos();
        for (int x = HeadlightMath.minimumSection(position.x, radius); x <= HeadlightMath.maximumSection(position.x, radius); x++) {
            for (int y = HeadlightMath.minimumSection(position.y, radius); y <= HeadlightMath.maximumSection(position.y, radius); y++) {
                for (int z = HeadlightMath.minimumSection(position.z, radius); z <= HeadlightMath.maximumSection(position.z, radius); z++) {
                    section.set(x, y, z);
                    SodiumDynamicLights.scheduleChunkRebuild(renderer, section);
                    SodiumDynamicLights.updateTrackedChunks(section, previous, next);
                }
            }
        }
    }
}

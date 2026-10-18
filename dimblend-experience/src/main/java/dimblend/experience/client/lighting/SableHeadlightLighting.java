package dimblend.experience.client.lighting;

import dev.ryanhcode.sable.Sable;
import dimblend.experience.Config;
import dimblend.experience.compat.dynlight.HeadlightMath;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.light.data.AreaLightData;
import foundry.veil.api.client.render.light.renderer.LightRenderHandle;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;

/** Supplies moving lamp data to Veil, using the same public API as Searchlights. */
public final class SableHeadlightLighting {
    private static final Map<SableHeadlightSource, LightRenderHandle<AreaLightData>> LIGHTS = new IdentityHashMap<>();
    private static ClientLevel currentLevel;

    private SableHeadlightLighting() {}

    public static void initialize() {
        NeoForge.EVENT_BUS.addListener(SableHeadlightLighting::render);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
    }

    public static boolean matches(Level level, BlockPos pos) {
        var id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return HeadlightMath.matches(id.getNamespace(), id.getPath());
    }

    public static void update(SableHeadlightSource source) {
        if (source.dimblend$isHeadlight() && source.sdl$getDynamicLightLevel() == Minecraft.getInstance().level) {
            LIGHTS.putIfAbsent(source, null);
        }
        else remove(source);
    }

    public static void remove(SableHeadlightSource source) {
        var handle = LIGHTS.remove(source);
        if (handle != null) handle.close();
    }

    private static void clear() {
        LIGHTS.values().forEach(handle -> { if (handle != null) handle.close(); });
        LIGHTS.clear();
        currentLevel = null;
        VeilHeadlightFramebuffer.restore();
    }

    private static void render(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clear(); return; }
        if (currentLevel != minecraft.level) {
            // Sources may already have arrived before the first frame in a new dimension.
            LIGHTS.entrySet().removeIf(entry -> {
                if (entry.getKey().sdl$getDynamicLightLevel() == minecraft.level) return false;
                if (entry.getValue() != null) entry.getValue().close();
                return true;
            });
            currentLevel = minecraft.level;
        }
        if (!LIGHTS.isEmpty() && (!Config.isLoaded() || (Config.SABLE_HEADLIGHT.get()
                && Config.SABLE_HEADLIGHT_BRIGHTNESS.get() > 0))) VeilHeadlightFramebuffer.synchronize();
        else VeilHeadlightFramebuffer.restore();
        for (var entry : LIGHTS.entrySet()) {
            SableHeadlightSource source = entry.getKey();
            var handle = entry.getValue();
            BlockPos plotPos = source.dimblend$plotPosition();
            var subLevel = Sable.HELPER.getContainingClient(plotPos);
            float brightness = Config.isLoaded() ? HeadlightMath.brightness(Config.SABLE_HEADLIGHT.get(),
                    source.dimblend$emission(), Config.SABLE_HEADLIGHT_BRIGHTNESS.get()) : 4.0F;
            if (subLevel == null || subLevel.isRemoved() || brightness <= 0) {
                if (handle != null) { handle.close(); entry.setValue(null); }
                continue;
            }
            if (handle == null || !handle.isValid()) {
                handle = VeilRenderSystem.renderer().getLightRenderer().addLight(new AreaLightData().setSize(0.3, 0.3));
                entry.setValue(handle);
            }
            var light = handle.getLightData();
            var state = minecraft.level.getBlockState(plotPos);
            if (!matches(minecraft.level, plotPos) || !state.hasProperty(BlockStateProperties.FACING)) {
                handle.close(); entry.setValue(null);
                continue;
            }
            Direction facing = state.getValue(BlockStateProperties.FACING);
            var pose = subLevel.renderPose();
            Vec3 position = pose.transformPosition(plotPos.getCenter());
            Vector3d direction = pose.orientation().transform(new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
            light.getPosition().set(position.x, position.y, position.z);
            // Veil area lights store the world-to-light rotation and emit along local +Z.
            HeadlightMath.worldToLight(direction, light.getOrientation());
            light.setBrightness(brightness);
            light.setDistance(Config.isLoaded() ? Config.SABLE_HEADLIGHT_RADIUS.get().floatValue() : 7.75F);
            light.setAngle((float) Math.toRadians((Config.isLoaded() ? Config.SABLE_HEADLIGHT_ANGLE.get() : 60.0D) / 2));
            light.setOcclusionEnabled(!Config.isLoaded() || Config.SABLE_HEADLIGHT_OCCLUSION.get());
            handle.markDirty();
        }
    }
}

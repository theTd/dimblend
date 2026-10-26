package dimblend.experience.compat.dndecor;

import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Per-placed-lamp activation; block states travel with vehicles, never with dropped items. */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class FrontlightSpotlight {
    public static final BooleanProperty ENABLED = BooleanProperty.create("dimblend_spotlight");

    public static boolean isFrontlight(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("dndecor") && id.getPath().endsWith("_frontlight")
                && state.hasProperty(BlockStateProperties.FACING) && state.hasProperty(BlockStateProperties.LIT);
    }

    public static boolean isEnabled(BlockState state) {
        return isFrontlight(state) && state.hasProperty(ENABLED) && state.getValue(ENABLED);
    }

    /**
     * Whether this lamp was sneak-placed without its back plate (DnD's {@code no_base} property): the lens then
     * sits a quarter block further out. Shared so the client collector and the server lamp scan agree.
     */
    public static boolean hasNoBase(BlockState state) {
        for (var property : state.getProperties()) {
            if (property.getName().equals("no_base") && Boolean.TRUE.equals(state.getValue(property))) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player.isSpectator() || !player.getAbilities().mayBuild
                || !event.getItemStack().is(Items.AMETHYST_SHARD)) return;
        var level = event.getLevel();
        var pos = event.getPos();
        var state = level.getBlockState(pos);
        if (!isFrontlight(state) || !state.hasProperty(ENABLED)) return;

        // Consume this interaction on both sides, including repeat clicks and sneak clicks.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (state.getValue(ENABLED) || !(level instanceof ServerLevel server)) return;
        if (!server.setBlock(pos, state.setValue(ENABLED, true), Block.UPDATE_CLIENTS)) return;
        // 激活消耗 1 个紫水晶（创造按惯例不消耗）；拆除掉落普通车灯、不返还水晶。
        if (!player.getAbilities().instabuild) event.getItemStack().shrink(1);

        // Sable stores the lamp in a distant plot; feedback belongs at its world position.
        var center = SableWorldPosition.project(level, pos.getCenter());
        server.playSound(null, center.x, center.y, center.z,
                SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        server.sendParticles(DustParticleOptions.REDSTONE, center.x, center.y, center.z,
                24, 0.3, 0.3, 0.3, 0.0);
    }

    private FrontlightSpotlight() { }
}

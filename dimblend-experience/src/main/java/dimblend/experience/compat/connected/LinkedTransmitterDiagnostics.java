package dimblend.experience.compat.connected;

import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.RaycastHelper;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableLinkedDiagnostics;
import dimblend.experience.mixin.PlayerStandingFitInvoker;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = DimBlend.MODID)
public final class LinkedTransmitterDiagnostics {
    private static final Map<SampleKey, PoseState> STATES = new ConcurrentHashMap<>();
    private static final Map<SampleKey, Long> LAST_LOG = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dbx")
                .then(Commands.literal("linkeddiag").requires(source -> source.hasPermission(2)).executes(context -> {
                    boolean enabled = !LinkedTransmitterDebugSettings.enabled();
                    LinkedTransmitterDebugSettings.setEnabled(enabled);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Linked transmitter diagnostics: " + enabled + " (see logs/latest.log)"), true);
                    return 1;
                })));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interactionStart(PlayerInteractEvent.RightClickBlock event) {
        if (!LinkedTransmitterDebugSettings.enabled()
                || !ModList.get().isLoaded("create_connected")
                || !LinkedTransmitterInteraction.supports(event.getLevel().getBlockState(event.getPos()), event.getEntity())) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        Player player = event.getEntity();
        BlockHitResult packet = LinkedTransmitterInteraction.packetHit(event);
        BlockHitResult legacy = RaycastHelper.rayTraceRange(event.getLevel(), player, 10.0D);
        StringBuilder frequencies = new StringBuilder();
        if (packet != null) {
            LinkBehaviour behaviour = BlockEntityBehaviour.get(event.getLevel(), event.getPos(), LinkBehaviour.TYPE);
            if (behaviour != null) {
                frequencies.append(" first=").append(behaviour.testHit(true, packet.getLocation()))
                        .append(",second=").append(behaviour.testHit(false, packet.getLocation()));
            }
        }
        DimBlend.LOGGER.info(
                "[linked-diag] side={} kind=interact-start player={}/{} time={} block={} pos={} hit={} packet={} legacyRay={} locked={} frequency={} hand={} item={} shift={} secondary={} crouch={} pose={} passenger={} fit={} eye={} aabb={} {}{}",
                event.getSide(), player.getGameProfile().getName(), player.getUUID(), event.getLevel().getGameTime(),
                state.getBlock().builtInRegistryHolder().getKey().location(), event.getPos(), hit(event.getHitVec()), hit(packet), hit(legacy),
                state.getValue(BlockStateProperties.LOCKED), frequencies, event.getHand(), event.getItemStack(),
                player.isShiftKeyDown(), player.isSecondaryUseActive(), player.isCrouching(), player.getPose(), player.isPassenger(),
                fits(player), player.getEyePosition(), box(player, Pose.STANDING), sublevels(player), "");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void interactionEnd(PlayerInteractEvent.RightClickBlock event) {
        if (!LinkedTransmitterDebugSettings.enabled()
                || !ModList.get().isLoaded("create_connected")
                || !LinkedTransmitterInteraction.supports(event.getLevel().getBlockState(event.getPos()), event.getEntity())) {
            return;
        }
        DimBlend.LOGGER.info("[linked-diag] side={} kind=interact-end player={} time={} pos={} canceled={} result={} (not final place result)",
                event.getSide(), event.getEntity().getUUID(), event.getLevel().getGameTime(), event.getPos(),
                event.isCanceled(), event.getCancellationResult());
    }

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post event) {
        if (LinkedTransmitterDebugSettings.enabled() && event.getEntity() instanceof ServerPlayer player) {
            sample(player, () -> "");
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.keySet().removeIf(key -> key.id().equals(event.getEntity().getUUID()));
        LAST_LOG.keySet().removeIf(key -> key.id().equals(event.getEntity().getUUID()));
    }

    public static void sample(Player player, java.util.function.Supplier<String> render) {
        if (!LinkedTransmitterDebugSettings.enabled()) {
            return;
        }
        long time = player.level().getGameTime();
        int id = player.getId();
        if (time % 5L != Math.floorMod(id, 5)) {
            return;
        }
        Fit fit = fits(player);
        PoseState current = new PoseState(player.isShiftKeyDown(), player.isCrouching(), player.getPose(), player.isPassenger(),
                player.getAbilities().flying, player.isSwimming(), player.isSleeping(), player.isSpectator(),
                player.isFallFlying(), player.isAutoSpinAttack(), fit);
        SampleKey key = new SampleKey(player.getUUID(), player.level().isClientSide);
        PoseState previous = STATES.put(key, current);
        boolean periodic = time % 20L == Math.floorMod(id, 20) && (!fit.standing() || player.isCrouching());
        if ((previous == null || !current.equals(previous) || periodic) && LAST_LOG.getOrDefault(key, Long.MIN_VALUE) != time) {
            LAST_LOG.put(key, time);
            DimBlend.LOGGER.info(
                    "[linked-diag] side={} kind=pose player={}/{} time={} shift={} secondary={} crouch={} pose={} passenger={} flying={} swimming={} sleeping={} spectator={} fallFlying={} spinAttack={} fit={} pos={} eyeY={} rot={}/{} aabb={} {}{}",
                    player.level().isClientSide ? "CLIENT" : "SERVER", player.getGameProfile().getName(), player.getUUID(), time,
                    player.isShiftKeyDown(), player.isSecondaryUseActive(), player.isCrouching(), player.getPose(), player.isPassenger(),
                    player.getAbilities().flying, player.isSwimming(), player.isSleeping(), player.isSpectator(), player.isFallFlying(),
                    player.isAutoSpinAttack(), fit, player.position(), player.getEyeY(), player.getYRot(), player.getXRot(),
                    box(player, Pose.STANDING), sublevels(player), render.get());
        }
    }

    public static void reset() {
        STATES.clear();
        LAST_LOG.clear();
    }

    private static Fit fits(Player player) {
        return new Fit(fit(player, Pose.STANDING), fit(player, Pose.CROUCHING), fit(player, Pose.SWIMMING));
    }

    private static boolean fit(Player player, Pose pose) {
        return ((PlayerStandingFitInvoker) player).dimblend$canFit(pose);
    }

    private static AABB box(Player player, Pose pose) {
        return player.getDimensions(pose).makeBoundingBox(player.position()).deflate(1.0E-7D);
    }

    private static String sublevels(Player player) {
        return ModList.get().isLoaded("sable") ? SableLinkedDiagnostics.describe(player) : " sub=[]";
    }

    private static String hit(BlockHitResult hit) {
        return hit == null ? "null" : hit.getType() + "/" + hit.getBlockPos() + "/" + hit.getLocation()
                + "/" + hit.getDirection() + "/inside=" + hit.isInside();
    }

    private record SampleKey(UUID id, boolean clientSide) {
    }

    private record Fit(boolean standing, boolean crouching, boolean swimming) {
        @Override
        public String toString() {
            return "S=" + standing + ",C=" + crouching + ",Sw=" + swimming;
        }
    }

    private record PoseState(boolean shift, boolean crouching, Pose pose, boolean passenger, boolean flying,
            boolean swimming, boolean sleeping, boolean spectator, boolean fallFlying, boolean spinAttack, Fit fit) {
    }

    private LinkedTransmitterDiagnostics() {
    }
}

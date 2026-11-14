package dimblend.dragonchase.carriage;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.structure.CarBlocks;
import dimblend.dragonchase.structure.CarKind;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The brake and crystal cars' bogeys cannot be adjusted, by anyone (requirements.md §3.5:
 * 车架不可调整).
 *
 * <p><b>What "adjusted" reaches.</b> Simurail's physical bogey opens its options menu from
 * {@code useItemOn} ({@code PhysicsBogeyBlock.java:143-153}); the wrench and the steering connector
 * reach the same method through the default block interaction, so a right-click opens the menu, a
 * wrench cycles the options and a connector re-wires steering. All three are refused by cancelling
 * use of the bogey block itself, whichever item is held; left-click and breaking are blocked as
 * well, because a broken bogey is the most decisive "adjustment" of all. The rest of a locked car is
 * not touched — only the bogey.
 *
 * <p><b>Plot positions, not world positions.</b> Sable's ray-cast overwrite
 * ({@code BlockGetterMixin#clip}) projects the hit into the sub-level's plot, so the position in a
 * player event on a car block already names a plot block; {@code Sable.HELPER.getContaining} turns it
 * back into the sub-level, which is how the car is recognised (identity, never block ids).
 *
 * <p><b>The nest is deliberately not locked.</b> requirements.md §3.3 nest-protection: the nest has
 * no extra edit lock — its blocks are survival-unbreakable copycats, and creative mode may do as it
 * likes. Only its bogey and couplers refuse a survival player's break, so the nest cannot be taken
 * off the rails by accident even when {@code dimblend-experience}'s guard is absent.
 *
 * <p><b>Known gap.</b> A client that sends {@code PhysicsBogeySetOptionsPacket} directly bypasses
 * block interaction; closing that would need a mixin inside Simurail, which this module must not add.
 */
public final class CarInteractionLock {

    private static final Set<CarKind> LOCKED = Set.of(CarKind.BRAKE, CarKind.CRYSTAL);

    private CarInteractionLock() {
    }

    /**
     * Blocks use of the bogey itself on a locked car.
     *
     * <p><b>Only the block matters.</b> Simurail opens its options menu from the bogey's own
     * {@code useItemOn}, and the wrench and the steering connector reach the same method through the
     * default block interaction — so cancelling every use of the bogey block covers all three
     * without looking at the item. The rest of the car stays usable: a coupler can still be clicked
     * and a lamp still inspected, whatever the player holds.
     */
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        CarKind kind = carKind(level, event.getPos());
        if (kind == null || !LOCKED.contains(kind) || !isBogey(level, event.getPos())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    /** Stops a break being started on a locked car's bogey, and on the nest's bogey or couplers. */
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!blocksBreak(level, event.getPos(), event.getEntity() != null && !event.getEntity().isCreative())) {
            return;
        }
        event.setCanceled(true);
        // LeftClickBlock carries no cancellation result; refusing the block action is how a break is
        // stopped before any mining progress starts (the reward wagon's guard does the same).
        event.setUseBlock(TriState.FALSE);
    }

    /** Refuses a break that was not started through an interaction (creative, machines, commands). */
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!blocksBreak(level, event.getPos(), event.getPlayer() != null && !event.getPlayer().isCreative())) {
            return;
        }
        event.setCanceled(true);
    }

    /**
     * True when a break of this block must be refused.
     *
     * <p>The brake and crystal cars refuse their bogey to everyone (§3.5: 车架不可调整). The nest is
     * not locked — decision nest-protection lets creative mode do as it likes — but a survival player
     * may not mine its bogey or couplers either, because those are the parts that keep it on the
     * rails. This holds even without {@code dimblend-experience}, whose own nest guard would
     * otherwise be the only protection.
     *
     * @param survival whether the player doing the breaking is not in creative mode
     */
    private static boolean blocksBreak(ServerLevel level, BlockPos pos, boolean survival) {
        CarKind kind = carKind(level, pos);
        if (kind == null) {
            return false;
        }
        if (kind == CarKind.NEST) {
            return survival && isBogeyOrCoupler(level, pos);
        }
        return LOCKED.contains(kind) && isBogey(level, pos);
    }

    /** The kind of the registered car this block belongs to, or {@code null}. */
    @Nullable
    private static CarKind carKind(ServerLevel level, BlockPos pos) {
        if (!(Sable.HELPER.getContaining(level, pos) instanceof ServerSubLevel car)) {
            return null;
        }
        return DragonCarriages.kindOf(level, car.getUniqueId());
    }

    /** True when the block is Simurail's physical bogey, by id so no class has to be loaded. */
    private static boolean isBogey(ServerLevel level, BlockPos pos) {
        return blockId(level, pos).equals(CarBlocks.BOGEY);
    }

    /** True when the block is the bogey or an automatic coupler. */
    private static boolean isBogeyOrCoupler(ServerLevel level, BlockPos pos) {
        String id = blockId(level, pos);
        return id.equals(CarBlocks.BOGEY) || id.equals(CarBlocks.COUPLER);
    }

    /** The registry id of the block at a plot position, for the no-class-loading comparisons. */
    private static String blockId(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}

package dimblend.experience.reward;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.common.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Wires the reward wagon's rules to the game's events.
 *
 * <p>Four guard paths, the same four the existing Simurail guard uses, because those are the
 * four a player can reach: breaking, left-clicking (which starts a break), right-clicking,
 * and an explosion. The difference is the policy: that guard lets creative players through,
 * this one refuses everyone, which is what the requirement asks and what the player chose.
 *
 * <p><b>The guard never sees the dissolve.</b> Dissolving a wagon calls
 * {@code removePartner} and sets air directly on the server thread; it raises no player
 * event, so locking the couplers for players and breaking them ourselves do not conflict.
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class RewardWagonEvents {
    private RewardWagonEvents() {
    }

    /** True when every mod this feature needs is present. */
    public static boolean available() {
        var mods = ModList.get();
        return mods.isLoaded("dimblend") && mods.isLoaded("dimblend_blocks") && mods.isLoaded("create")
                && mods.isLoaded("copycats") && mods.isLoaded("simurail") && mods.isLoaded("sable")
                && mods.isLoaded("twilightforest") && mods.isLoaded("eternal_starlight")
                && mods.isLoaded("deeperdarker");
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!available() || !Config.REWARD_WAGON.get()) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (RewardWagonBuilder.inDimension(level)) {
                RewardWagonTicker.tick(level);
            }
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !guarded(level)) {
            return;
        }
        if (RewardWagonGuard.denies(verdict(level, event.getPos(), true, false))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !guarded(level)) {
            return;
        }
        if (RewardWagonGuard.denies(verdict(level, event.getPos(), true, false))) {
            event.setCanceled(true);
            // LeftClickBlock carries no cancellation result; refusing the block action is how
            // a break is stopped before any mining progress starts.
            event.setUseBlock(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !guarded(level)) {
            return;
        }
        // The motor's rpm is a wrench scroll; the requirement pins it at 256 and says it
        // cannot be adjusted. Only the motor's own cell is affected — the lever must still be
        // pullable, or the wagon could never be started.
        if (brickOf(level, event.getPos()) == RewardWagonGuard.Brick.MOTOR
                && insideWagon(level, event.getPos())
                && event.getItemStack().is(com.simibubi.create.AllItems.WRENCH)
                && RewardWagonGuard.denies(RewardWagonGuard.mayAdjustMotorSpeed(true))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }
        if (RewardWagonGuard.denies(verdict(level, event.getPos(), false, true))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !guarded(level)) {
            return;
        }
        RewardWagonGuard.Source source = event.getEntity() instanceof Player
                ? RewardWagonGuard.Source.PLAYER : RewardWagonGuard.Source.OTHER;
        if (RewardWagonGuard.denies(RewardWagonGuard.mayPlace(insideWagon(level, event.getPos()), source))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplode(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !guarded(level)) {
            return;
        }
        // An explosion may take content but never structure: dropping the affected structure
        // blocks from the list leaves the explosion intact for everything else.
        event.getAffectedBlocks().removeIf(pos -> RewardWagonGuard.denies(
                RewardWagonGuard.mayRemove(insideWagon(level, pos), brickOf(level, pos),
                        RewardWagonGuard.Source.OTHER)));
    }

    /**
     * The event-side view of {@link RewardWagonGuard}: finds the wagon, the brick and the
     * source, and asks the pure rule.
     */
    private static RewardWagonGuard.Verdict verdict(ServerLevel level, BlockPos pos, boolean removing,
            boolean interacting) {
        if (!insideWagon(level, pos)) {
            return RewardWagonGuard.Verdict.ALLOW;
        }
        RewardWagonGuard.Brick brick = brickOf(level, pos);
        if (removing) {
            return RewardWagonGuard.mayRemove(true, brick, RewardWagonGuard.Source.PLAYER);
        }
        if (interacting) {
            return RewardWagonGuard.mayInteract(true, brick);
        }
        return RewardWagonGuard.Verdict.ALLOW;
    }

    /**
     * True when this block belongs to a reward wagon sub-level.
     *
     * <p><b>Identity, not block id.</b> The site record holds the UUID of the sub-level it
     * assembled into, so a wagon is recognised wherever its blocks have been pushed. A
     * block-id test was the first version and it was wrong: a motor wagon's motor, clutch and
     * lever are not the three brick kinds, so that whole side of the deck was left with no
     * guard at all and a player could place blocks there.
     */
    static boolean insideWagon(ServerLevel level, BlockPos pos) {
        SubLevel containing = Sable.HELPER.getContaining(level, pos);
        if (!(containing instanceof ServerSubLevel)) {
            return false;
        }
        return RewardWagonSites.get(level).findBySubLevel(containing.getUniqueId()) != null;
    }

    /**
     * True when this block can only belong to a reward wagon.
     *
     * <p>The clean copycat slab and the bogey appear nowhere else in this dimension, so
     * checking the block id is enough to tell a wagon from any other contraption a player
     * builds. The couplers are more common, so a coupler counts only inside a sub-level whose
     * centre carries the wagon's own blocks — see {@link #brickOf}.
     */
    static boolean isWagonBlock(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return id.equals(RewardWagonBlocks.COPYCAT_SLAB) || id.equals(RewardWagonBlocks.BOGEY)
                || id.equals(RewardWagonBlocks.COUPLER);
    }

    static RewardWagonGuard.Brick brickOf(ServerLevel level, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        if (id.equals(RewardWagonBlocks.BOGEY)) {
            return RewardWagonGuard.Brick.BOGEY;
        }
        if (id.equals(RewardWagonBlocks.COPYCAT_SLAB)) {
            return RewardWagonGuard.Brick.SLAB;
        }
        if (id.equals(RewardWagonBlocks.COUPLER)) {
            return RewardWagonGuard.Brick.COUPLER;
        }
        if (id.equals(RewardWagonBlocks.CREATIVE_MOTOR)) {
            return RewardWagonGuard.Brick.MOTOR;
        }
        return RewardWagonGuard.Brick.CONTENT;
    }

    private static boolean guarded(ServerLevel level) {
        return available() && Config.REWARD_WAGON.get() && RewardWagonBuilder.inDimension(level);
    }
}

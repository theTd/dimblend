package dimblend.experience.compat.create.stockkeeper;

import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerInteractionHandler;
import com.simibubi.create.foundation.gui.menu.MenuBase;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

/** Keeps Create's seat, unique-ticker and permission rules; only adds the registered doll entity. */
public final class DollStockKeeperCompat {
    public static final ResourceLocation DOLL = ResourceLocation.fromNamespaceAndPath("kaleidoscope_doll", "doll");
    public static final ResourceLocation SQUEAK =
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_doll", "block.duck_toy");

    private DollStockKeeperCompat() { }

    public static boolean isDoll(@Nullable Entity entity) {
        return entity != null && DOLL.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
    }

    /** Same eight seat cells as Create's screen, with the authoritative interaction predicate. */
    @Nullable
    public static Entity findKeeper(StockTickerBlockEntity ticker) {
        Level level = ticker.getLevel();
        if (level == null || ticker.isRemoved()) return null;
        for (int yOffset : Iterate.zeroAndOne) {
            for (Direction side : Iterate.horizontalDirections) {
                BlockPos seatPos = ticker.getBlockPos().below(yOffset).relative(side);
                for (SeatEntity seat : level.getEntitiesOfClass(SeatEntity.class, new AABB(seatPos))) {
                    if (seat.getPassengers().isEmpty()) continue;
                    Entity keeper = seat.getPassengers().getFirst();
                    if (isValidKeeper(keeper, seat, ticker.getBlockPos())) return keeper;
                }
            }
        }
        return null;
    }

    /** A screen belongs to its original doll and seat, never a replacement nearby. */
    public static boolean isValidKeeper(@Nullable Entity doll, @Nullable Entity originalSeat, BlockPos tickerPos) {
        return isDoll(doll) && doll.isAlive() && originalSeat instanceof SeatEntity && originalSeat.isAlive()
                && doll.getVehicle() == originalSeat && doll.getRootVehicle() == originalSeat
                && tickerPos.equals(StockTickerInteractionHandler.getStockTickerPosition(doll));
    }

    /**
     * Create returns true even for permission denial and shopping-list interactions. Only an actual
     * menu replacement for this ticker counts. Request and category menus are sibling MenuBase types.
     * Shared by both keeper entries (real DollEntity and the upstream proxy keeper seat).
     */
    public static boolean openedMenuFor(ServerPlayer player, Level level, BlockPos tickerPos,
                                        AbstractContainerMenu previousMenu) {
        AbstractContainerMenu current = player.containerMenu;
        return current != previousMenu && current instanceof MenuBase<?> menu
                && menu.contentHolder instanceof StockTickerBlockEntity ticker
                && ticker.getLevel() == level && tickerPos.equals(ticker.getBlockPos());
    }

    /**
     * 随机音高（2026-11-25 用户口径：售货员玩偶每次吱一声的音高要有起伏，别每次都一样）：
     * {@code randomUnit ∈ [0, 1)} 线性映射到 {@code [0.75, 1.25)}，中位 1.0。
     *
     * <p>纯函数，单测钉边界（0 → 0.75、1 → 1.25、0.5 → 1.0）。原版音高落在
     * {@code 0.75~1.25} 这个区间内 IRL 不会失真，也仍在 {@code playSound} 的音高合法范围内。</p>
     */
    public static float computeSqueakPitch(float randomUnit) {
        return 0.75F + randomUnit * 0.5F;
    }

    /** One squeak, broadcast by the server; both keeper entries route their success through here. */
    public static void squeak(Level level, double x, double y, double z) {
        float pitch = computeSqueakPitch(level.random.nextFloat());
        BuiltInRegistries.SOUND_EVENT.getOptional(SQUEAK).ifPresent(sound ->
                level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, 1.0f, pitch));
    }

    /** The real DollEntity entry: a doll entity was right-clicked on a Create seat. */
    public static void afterInteraction(PlayerInteractEvent.EntityInteractSpecific event, BlockPos tickerPos,
                                        AbstractContainerMenu previousMenu) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getLevel().isClientSide()
                || event.getHand() != InteractionHand.MAIN_HAND || !isDoll(event.getTarget())) return;
        if (!openedMenuFor(player, event.getLevel(), tickerPos, previousMenu)) return;
        Entity doll = event.getTarget();
        squeak(event.getLevel(), doll.getX(), doll.getY(), doll.getZ());
    }
}
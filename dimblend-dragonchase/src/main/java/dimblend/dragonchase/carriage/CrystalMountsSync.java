package dimblend.dragonchase.carriage;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.dragonchase.entity.CrystalMountsPayload;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.CarMotion;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Tells clients which crystals ride which car ({@link CrystalMountsPayload}).
 *
 * <p><b>One broadcast per change, not per tick.</b> The list is {@code (entityId, carId, plotCell)}
 * per live crystal; none of those move while the car drives, so a broadcast is only needed when a
 * crystal spawns, dies or a car appears. The comparison is a list equality over at most a handful of
 * entries, which is cheaper than packet construction by orders of magnitude.
 *
 * <p><b>Late joiners.</b> A player who logs in or changes dimension has no mount list, so the server
 * pushes the current one to that player alone ({@link #sendTo}). A player entering a dimension that
 * is not the corridor gets an empty list, which drops whatever the client still held for the
 * dimension it left.
 *
 * <p>Server thread only: it reads the registry and the Sable container, and sends packets.
 */
public final class CrystalMountsSync {

    /** The level the cached list belongs to; a new world is a new level object. */
    @Nullable
    private static ServerLevel cachedLevel;
    private static List<CrystalMountsPayload.Entry> cached = List.of();

    /**
     * Broadcasts the mount list when it changed since the previous tick.
     *
     * @param container the level's container, or {@code null} when Sable has none (then no crystal
     *                  can be mounted, so the list is empty)
     */
    public static void tick(ServerLevel level, @Nullable ServerSubLevelContainer container, CarRegistry registry) {
        List<CrystalMountsPayload.Entry> current = collect(level, container, registry);
        if (level == cachedLevel && current.equals(cached)) {
            return;
        }
        cachedLevel = level;
        cached = current;
        PacketDistributor.sendToPlayersInDimension(level, new CrystalMountsPayload(current));
    }

    /** Pushes the current mount list to one player, empty when that player is not in the corridor. */
    public static void sendTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!CarTicker.isCorridor(level)) {
            PacketDistributor.sendToPlayer(player, new CrystalMountsPayload(List.of()));
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        PacketDistributor.sendToPlayer(player,
                new CrystalMountsPayload(collect(level, container, CarRegistry.get(level))));
    }

    /**
     * The live mounts of every crystal car in {@code registry}, in car then spawn order.
     *
     * <p>A crystal is only listed when its entity is in the level: the client resolves the id with
     * {@code ClientLevel#getEntity}, so an unloaded crystal would be a no-op entry. The list changes
     * when the entity loads, which is exactly when the client needs to hear about it.
     */
    static List<CrystalMountsPayload.Entry> collect(ServerLevel level, @Nullable ServerSubLevelContainer container,
            CarRegistry registry) {
        if (container == null) {
            return List.of();
        }
        List<CrystalMountsPayload.Entry> entries = new ArrayList<>();
        for (CarRegistry.Car record : registry.snapshot()) {
            if (record.removed || record.kind != CarKind.CRYSTAL || record.crystals.isEmpty()) {
                continue;
            }
            if (!(container.getSubLevel(record.id) instanceof ServerSubLevel car) || car.isRemoved()) {
                continue;
            }
            for (CarRegistry.Crystal slot : record.crystals) {
                Entity entity = level.getEntity(slot.id());
                if (!(entity instanceof EndCrystal crystal) || crystal.isRemoved()) {
                    continue;
                }
                BlockPos plot = CarMotion.plotPos(car, slot.dx(), slot.dy(), slot.dz());
                entries.add(new CrystalMountsPayload.Entry(crystal.getId(), record.id,
                        plot.getX(), plot.getY(), plot.getZ()));
            }
        }
        return List.copyOf(entries);
    }

    private CrystalMountsSync() {
    }
}

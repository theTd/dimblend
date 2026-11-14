package dimblend.experience.chassis;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.api.DepotCarQuery;
import dimblend.experience.mixin.compat.simurail.AutomaticCouplerPartnerAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

/**
 * {@link DepotCarQuery} over the station depot's own records (plan §0 Q1).
 *
 * <p>Answer: true when every carriage of the connected consist containing {@code subLevel} is
 * <b>still parked at its own depot site</b>: each one is the car a depot site assembled (its
 * persisted {@code car_id}) and its world bounding-box centre is within
 * {@link #PARKED_MAX_DX} x {@link #PARKED_MAX_DZ} of that site's centre. Coupling alone no longer
 * decides the answer, because both of its readings were wrong in play:
 *
 * <ul>
 *   <li>Two spare depot cars coupled together <i>on their siding</i> used to answer false ("has a
 *       partner"), and the Dragon Chase's 2 m/s floor then shoved the parked spare consist into the
 *       end of the siding.
 *   <li>A solo depot {@code POWER} car a player drove 60,000 blocks down the corridor used to answer
 *       true (recorded, uncoupled), which exempted the player's own locomotive from the floor.
 * </ul>
 *
 * <p>Therefore the walk is over the whole coupler component, never just the queried car: a player
 * carriage hitched to a parked spare, or one depot car that already left its box, refuses the whole
 * component. An unresolved link ({@code partnerPos != null}, {@code partnerSubLevelId == null}) or a
 * partner whose sub-level is gone also refuses it — a car on the far side of a dead link must not be
 * waved through as "uncoupled".
 *
 * <p>Read-only, server thread, loaded sub-levels only: plot actors and Sable's already-computed
 * global bounds, never {@code getChunk} or a forced load.
 *
 * <p>Registered at mod construction when Simurail is present: the coupler type comes from there.
 */
public final class DepotCarQueryImpl implements DepotCarQuery {

    /** How far a car may drift from its site centre in X and still count as parked there. */
    static final double PARKED_MAX_DX = 32.0D;
    /** How far a car may drift from its site centre in Z and still count as parked there. */
    static final double PARKED_MAX_DZ = 16.0D;

    private static final DepotCarQueryImpl INSTANCE = new DepotCarQueryImpl();

    /** Called next to {@code TrainDeadmanBrake.register()} behind the same Simurail gate. */
    public static void register() {
        DepotCarQuery.register(INSTANCE);
    }

    @Override
    public boolean isIdleDepotCar(ServerLevel level, UUID subLevel) {
        if (level == null || subLevel == null) {
            return false;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        Map<UUID, BlockPos> sites = depotSites(level);
        if (sites.isEmpty()) {
            return false;
        }
        Map<UUID, Car> component = walkComponent(container, subLevel, sites);
        return parkedConsist(component, subLevel);
    }

    /** Every sub-level a depot site assembled, keyed by that car's id, with the site's centre. */
    private static Map<UUID, BlockPos> depotSites(ServerLevel level) {
        Map<UUID, BlockPos> sites = new HashMap<>();
        for (StationDepotAssemblyData.Site site : StationDepotAssemblyData.get(level).sites) {
            if (site.assembled && site.carId != null) {
                sites.put(site.carId, site.center);
            }
        }
        return sites;
    }

    /**
     * Breadth-first walk of the coupler graph from {@code start}. Every discovered id is put in the
     * result, with {@code null} for a partner the container no longer holds, so the pure rule can
     * tell "dead link" from "not visited". Never loads a chunk: plot actors and bounding boxes only.
     */
    private static Map<UUID, Car> walkComponent(SubLevelContainer container, UUID start,
                                                Map<UUID, BlockPos> sites) {
        Map<UUID, Car> component = new HashMap<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            if (component.containsKey(id)) {
                continue; // Cycle A <-> B <-> A: already described.
            }
            SubLevel car = container.getSubLevel(id);
            if (car == null || car.isRemoved()) {
                component.put(id, null);
                continue;
            }
            Car node = describe(car, sites.get(id));
            component.put(id, node);
            for (Coupler coupler : node.couplers) {
                if (coupler.partnerId != null && !component.containsKey(coupler.partnerId)) {
                    queue.add(coupler.partnerId);
                }
            }
        }
        return component;
    }

    /** World-box centre plus every coupler of one loaded carriage, as plain data. */
    private static Car describe(SubLevel car, BlockPos site) {
        Vector3d center = new Vector3d();
        car.boundingBox().center(center);
        List<Coupler> couplers = new ArrayList<>();
        for (var actor : car.getPlot().getBlockEntityActors()) {
            if (actor instanceof AutomaticCouplerBlockEntity coupler && !coupler.isRemoved()) {
                AutomaticCouplerPartnerAccessor access = (AutomaticCouplerPartnerAccessor) coupler;
                UUID partner = access.dimblend$getPartnerSubLevelId();
                boolean unresolved = partner == null && access.dimblend$getPartnerPos() != null;
                couplers.add(new Coupler(partner, unresolved));
            }
        }
        boolean depot = site != null;
        return new Car(center.x, center.z,
                depot, depot ? site.getX() : 0.0D, depot ? site.getZ() : 0.0D, couplers);
    }

    /**
     * The rule itself, without a single Minecraft or Sable object: true iff every carriage reachable
     * from {@code start} is a depot car still inside its own park box, and no link on the way is
     * unresolved or dangling.
     *
     * <p>{@code component} must hold an entry (possibly {@code null}) for every id it was asked
     * about; a partner id with no entry reads as a dead link, like a {@code null} entry.
     */
    static boolean parkedConsist(Map<UUID, Car> component, UUID start) {
        if (start == null || component == null || component.isEmpty()) {
            return false;
        }
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            UUID id = queue.poll();
            if (!visited.add(id)) {
                continue; // Cyclic coupling terminates here.
            }
            Car car = component.get(id);
            if (car == null || !car.depot || !car.parked()) {
                return false;
            }
            for (Coupler coupler : car.couplers) {
                if (coupler.unresolved) {
                    return false;
                }
                if (coupler.partnerId == null) {
                    continue; // Free coupler.
                }
                if (!component.containsKey(coupler.partnerId)) {
                    return false; // Partner sub-level is gone: not a parked spare consist.
                }
                queue.add(coupler.partnerId);
            }
        }
        return true;
    }

    /** One carriage of the walk: its world-box centre and its own depot record, if it has one. */
    static final class Car {
        final double centerX;
        final double centerZ;
        final boolean depot;
        final double siteX;
        final double siteZ;
        final List<Coupler> couplers;

        Car(double centerX, double centerZ, boolean depot, double siteX, double siteZ,
            List<Coupler> couplers) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.depot = depot;
            this.siteX = siteX;
            this.siteZ = siteZ;
            this.couplers = couplers;
        }

        /** True when the world-box centre is still within the site's parking box. */
        boolean parked() {
            return Math.abs(centerX - siteX) <= PARKED_MAX_DX
                    && Math.abs(centerZ - siteZ) <= PARKED_MAX_DZ;
        }
    }

    /**
     * One automatic coupler of a carriage. {@code partnerId} is the linked sub-level, or null when
     * the coupler is free; {@code unresolved} marks the link that knows its {@code partnerPos} but
     * has not resolved a sub-level yet.
     */
    static final class Coupler {
        final UUID partnerId;
        final boolean unresolved;

        Coupler(UUID partnerId, boolean unresolved) {
            this.partnerId = partnerId;
            this.unresolved = unresolved;
        }
    }

    private DepotCarQueryImpl() {
    }
}
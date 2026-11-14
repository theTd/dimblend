package dimblend.dragonchase.structure;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * The car body's motion through Sable's rigid-body handle: the one place velocities are read and
 * written, so no rule has to know the handle exists.
 *
 * <p><b>Units are metres per second.</b> Sable's physics runs in m/s (the warp-gate push in
 * {@code dimblend} uses the requirement's 1 m/s directly), so every speed this module compares
 * against — the 2 m/s "pushed uncoupled" threshold, the launch impulse — is used as-is. The trap is
 * vanilla's blocks per tick: 2 m/s is 0.1 blocks per tick, not 2.
 *
 * <p><b>There is no setter.</b> {@code RigidBodyHandle} exposes only
 * {@code addLinearAndAngularVelocity}, so "set the X velocity to v" is expressed as the delta that
 * makes it so, leaving Y and Z untouched. Writing a body's velocity, never its position, is also
 * what keeps the solver in charge (the warp-gate rule in {@code WarpGateSableCrossing} reached the
 * same conclusion after a teleport-based version broke collisions).
 */
public final class CarMotion {

    private CarMotion() {
    }

    /** The body handle of a live sub-level, or {@code null} when Sable has none for it. */
    @Nullable
    public static RigidBodyHandle handle(ServerSubLevel car) {
        if (car == null || car.isRemoved()) {
            return null;
        }
        RigidBodyHandle handle = RigidBodyHandle.of(car);
        return handle != null && handle.isValid() ? handle : null;
    }

    /** The linear velocity in m/s, or zero when the car has no usable handle. */
    public static Vector3d velocity(ServerSubLevel car) {
        RigidBodyHandle handle = handle(car);
        return handle == null ? new Vector3d() : handle.getLinearVelocity(new Vector3d());
    }

    /** The speed in m/s, or 0 when the car has no usable handle. */
    public static double speed(ServerSubLevel car) {
        return velocity(car).length();
    }

    /**
     * Makes the car's velocity exactly {@code (vx, vy, vz)} m/s.
     *
     * @return false when the car has no usable handle, so nothing was written
     */
    public static boolean setVelocity(ServerSubLevel car, double vx, double vy, double vz) {
        RigidBodyHandle handle = handle(car);
        if (handle == null) {
            return false;
        }
        Vector3d current = handle.getLinearVelocity(new Vector3d());
        handle.addLinearAndAngularVelocity(
                new Vector3d(vx - current.x, vy - current.y, vz - current.z), new Vector3d());
        return true;
    }

    /** A plot block position of a live car, in the world. */
    public static Vec3 worldOf(ServerSubLevel car, BlockPos plotPos) {
        return car.logicalPose().transformPosition(Vec3.atCenterOf(plotPos));
    }

    /**
     * The world position of one of the car's cells.
     *
     * <p>The plot anchor is where the assembly helper put the car's centre block:
     * {@code SubLevelAssemblyHelper#assembleBlocks} builds its transform as
     * {@code (anchor = centre, resultingAnchor = plot.getCenterBlock())} with no rotation
     * ({@code SubLevelAssemblyHelper.java:106-107}) and {@code apply} subtracts the anchor and adds
     * the plot anchor ({@code :538-547}), so a cell offset maps one-to-one onto the plot. Reading
     * the anchor back from {@code plot.getCenterBlock()} is the identity Sable itself used.
     */
    public static BlockPos plotPos(ServerSubLevel car, CarPlans.Cell cell) {
        return plotPos(car, cell.dx(), cell.dy(), cell.dz());
    }

    /** The plot position of a layout offset, for the cells a record stores as plain offsets. */
    public static BlockPos plotPos(ServerSubLevel car, int dx, int dy, int dz) {
        return car.getPlot().getCenterBlock().offset(dx, dy, dz);
    }

    /** The world position of a cell of the car, e.g. the bogey's. */
    public static Vec3 worldOf(ServerSubLevel car, CarPlans.Cell cell) {
        return worldOf(car, plotPos(car, cell));
    }

    /** True when the car's body could be read, logged once per call site when it could not. */
    public static void warnMissingHandle(ServerSubLevel car) {
        DimBlendDragonChase.LOGGER.warn("Dragon Chase car {} has no usable rigid body; velocity not applied",
                car == null ? "?" : car.getUniqueId());
    }
}

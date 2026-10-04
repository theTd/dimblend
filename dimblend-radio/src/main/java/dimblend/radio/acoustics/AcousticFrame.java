package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * The coordinates a simulation runs in: the world's, or a Sable structure's own (its plot's). A
 * listener riding a structure, and the radios on board, stand still in the structure's frame
 * while it moves, so what Steam Audio sees of them (and averages over its runs) settles there;
 * what does not ride along (terrain, other structures) is placed into the frame instead.
 * <p>
 * Poses are taken to scale uniformly, as Sable's structures do.
 */
public final class AcousticFrame {
    public static final AcousticFrame WORLD = new AcousticFrame(null, null);
    private static final Pose3dc IDENTITY = new Pose3d();

    private final UUID structure;
    /** The structure's frame to the world, as of the moment the frame was taken; null for the world. */
    private final Pose3d pose;

    private AcousticFrame(UUID structure, Pose3d pose) {
        this.structure = structure;
        this.pose = pose;
    }

    /** {@code structure}'s frame, where {@code pose} places it now (copied). */
    public static AcousticFrame of(UUID structure, Pose3dc pose) {
        return new AcousticFrame(Objects.requireNonNull(structure), new Pose3d(pose));
    }

    public boolean world() { return structure == null; }

    /** The structure whose frame this is, or null for the world's. */
    public UUID structure() { return structure; }

    /** The structure's frame to the world as of when this was taken, or null for the world's. */
    public Pose3dc pose() { return pose; }

    /** Same coordinates, wherever the structure has moved since: both the world's, or both one structure's. */
    public boolean sameAxes(AcousticFrame other) {
        return Objects.equals(structure, other.structure);
    }

    /** A world position in this frame. */
    public Vec3 toLocal(Vec3 world) {
        return pose == null ? world : pose.transformPositionInverse(world);
    }

    /** A position in this frame, in the world. */
    public Vec3 toWorld(Vec3 local) {
        return pose == null ? local : pose.transformPosition(local);
    }

    /** A world direction (or normal) in this frame, unscaled. */
    public Vec3 directionToLocal(Vec3 world) {
        if (pose == null) return world;
        Vector3d direction = pose.orientation().transformInverse(new Vector3d(world.x, world.y, world.z));
        return new Vec3(direction.x, direction.y, direction.z);
    }

    /** The rotation from this frame's axes to the world's. */
    public Quaterniondc orientation() {
        return pose == null ? new Quaterniond() : pose.orientation();
    }

    /**
     * The pose that places what {@code placed} places in the world into this frame instead: a
     * structure's pose relative to this one. {@code null} stands for the world itself (terrain).
     * <p>
     * The world's pose turns about where the structure is, not the world's origin: poses are
     * compared part by part ({@link AcousticUpdateGate#samePose}), and about the origin a turn far
     * below the tolerance would move the position thousands of blocks out by more than it.
     */
    public Pose3d relative(Pose3dc placed) {
        if (pose == null) return new Pose3d(placed == null ? IDENTITY : placed);
        double scale = pose.scale().x();
        Quaterniond back = new Quaterniond(pose.orientation()).conjugate();
        if (placed == null) {
            return new Pose3d(new Vector3d(pose.rotationPoint()), back, new Vector3d(pose.position()), new Vector3d(1 / scale));
        }
        Vector3d position = pose.transformPositionInverse(placed.position(), new Vector3d());
        return new Pose3d(position, back.mul(placed.orientation()), new Vector3d(placed.rotationPoint()),
                new Vector3d(placed.scale()).div(scale));
    }

    @Override public String toString() {
        return structure == null ? "world" : "structure " + structure;
    }
}

package dimblend.experience.compat.sable;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.crystaelix.simurail.api.physics.RailJointLocks;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisBlueprint;
import dimblend.experience.chassis.ChassisEvents;
import dimblend.experience.chassis.ChassisPose;
import dimblend.experience.chassis.ChassisRailLockRules;
import dimblend.experience.chassis.ChassisRegistry;
import dimblend.experience.chassis.ChassisRegistryData;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * {@code dimblend:rotating} 已登记标准车架的生产世界轨锁。
 *
 * <p><b>作用。</b>对每台已登记、蓝图标准、必要转向架完整且挂轨的车体，在 Sable/Rapier
 * 里建一个 {@code bodyA == null} 的世界 generic joint：锁 {@code LINEAR_Y}、
 * {@code LINEAR_Z}、{@code ANGULAR_X/Y/Z}，只留 {@code LINEAR_X}。车体因此只能沿世界 X
 * 平移，不能抬升、下沉、横向漂移、俯仰、横滚或偏航。</p>
 *
 * <p><b>坐标。</b>关节锚点取 plot 中心块上方的 deck 顶面：
 * {@code plotCenter.getCenter() + (0, 0.5, 0)}。标准装配把世界锚点块（y=65）映射到
 * plot 中心，deck 顶面因此是 plot y+0.5；世界 Y 用登记里的标称高度（新装配
 * {@code anchorY+1}，例如 66），世界 Z 用中心下方 XO 轨道块的中心（例如 z+0.5）。
 * 关节帧取目标朝向的绕 Y 世界帧，车体局部帧为单位帧，所以 pitch/roll 归零、yaw
 * 固定为 0° 或 180°。</p>
 *
 * <p><b>为什么不会每 tick teleport。</b>只调用
 * {@code PhysicsPipeline#addConstraint} 建一次约束；没有任何 {@code teleport}、没有
 * 每 tick 改 pose、没有改质量/驱动/应力/制动，也没有改 SimuRail 的轮轨 joint。</p>
 *
 * <p><b>生命周期。</b>登记扫描：每 {@link ChassisRailLockRules#CHECK_INTERVAL_TICKS} tick
 * 看一次登记表，连续 {@link ChassisRailLockRules#STABLE_CHECKS} 次资格通过才建约束。
 * 已建约束每 tick 复核，脱轨、轨道不再是直线 X、误差超出保持窗口、句柄失效、目标
 * 卸载或移除时立即释放。{@link SubLevelObserver} 在 {@code UNLOADED}/{@code REMOVED}
 * 时释放；重载后由登记扫描恢复。服务器停止/维度卸载清空。</p>
 *
 * <p><b>边界。</b>只认 {@link ChassisRegistry} 里的 POWER/CARRIAGE 身份；reward wagon
 * 和未登记车体不会进入。曲线、道岔、坡道、Y/Z 轨道以及 90° 朝向会因轨道不是 XO 或
 * yaw 误差过大而保持未锁定/释放，不改动 SimuRail 原有行为。</p>
 */
public final class ChassisRailLock {
    private static final Logger LOGGER = DimBlend.LOGGER;
    private static final Set<ConstraintJointAxis> LOCKED_AXES =
            constraintAxes(ChassisRailLockRules.lockedAxes());
    private static final Quaterniond IDENTITY = new Quaterniond();
    private static final int MAX_REMOVE_ATTEMPTS = 200;
    private static final int FAILURE_LOG_INTERVAL_TICKS = 200;

    private static final Map<ServerLevel, LevelState> LEVELS = new LinkedHashMap<>();
    private static final Map<GenericConstraintHandle, PendingRemoval> PENDING = new LinkedHashMap<>();

    /**
     * 每服务器 tick 调用一次（只在 {@code dimblend:rotating}）。
     */
    public static void tick(ServerLevel level) {
        if (level == null || !ChassisEvents.inDimension(level)) {
            return;
        }
        LevelState state = state(level);
        if (state == null) {
            return;
        }
        try {
            state.tick();
        } catch (RuntimeException failure) {
            state.logFailure("tick", failure);
        }
    }

    /** 登记/重新登记后调用：清零稳定计数，让下一轮扫描从头检查。 */
    public static void request(ServerLevel level, UUID id) {
        if (level == null || id == null || !ChassisEvents.inDimension(level)) {
            return;
        }
        LevelState state = state(level);
        if (state != null) {
            state.stable.put(id, 0);
        }
    }

    /** 校准/注销时调用：立刻释放旧约束，避免继续按旧标称高度保持。 */
    public static void release(ServerLevel level, UUID id, String reason) {
        if (level == null || id == null) {
            return;
        }
        LevelState state = LEVELS.get(level);
        if (state != null) {
            state.releaseInternal(id, reason);
        }
    }

    /** 维度卸载：释放该维度全部约束，不写生命周期。 */
    public static void forget(ServerLevel level) {
        if (level == null) {
            return;
        }
        LevelState state = LEVELS.remove(level);
        if (state != null) {
            state.releaseAll("level_unload");
        }
    }

    /** 服务器停止：释放全部约束并清空状态。 */
    public static void shutdown() {
        for (LevelState state : List.copyOf(LEVELS.values())) {
            try {
                state.releaseAll("server_stopping");
            } catch (RuntimeException failure) {
                LOGGER.warn("[chassis-lock] release during shutdown failed", failure);
            }
        }
        LEVELS.clear();
        PENDING.clear();
    }

    /** 命令/诊断用：LOCKED / LOCKED(stale) / STABILIZING(n/3) / UNLOCKED。 */
    public static String status(ServerLevel level, UUID id) {
        if (level == null || id == null) {
            return "UNLOCKED";
        }
        LevelState state = LEVELS.get(level);
        if (state == null) {
            return "UNLOCKED";
        }
        ActiveLock active = state.active.get(id);
        if (active != null) {
            return valid(active.joint()) ? "LOCKED" : "LOCKED(stale)";
        }
        Integer checks = state.stable.get(id);
        if (checks != null && checks > 0) {
            return "STABILIZING(" + checks + "/" + ChassisRailLockRules.STABLE_CHECKS + ")";
        }
        return "UNLOCKED";
    }

    /** 当前活动约束数（诊断）。 */
    public static int lockedCount(ServerLevel level) {
        LevelState state = LEVELS.get(level);
        return state == null ? 0 : state.active.size();
    }

    private static LevelState state(ServerLevel level) {
        LevelState existing = LEVELS.get(level);
        if (existing != null) {
            return existing;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        LevelState created = new LevelState(level, container);
        container.addObserver(created);
        LEVELS.put(level, created);
        return created;
    }

    private record ActiveLock(UUID id, GenericConstraintHandle joint) {
    }

    private record TrackCheck(boolean loaded, boolean straight, BlockPos pos) {
    }

    private record Evaluation(ChassisRailLockRules.Context context, Vector3d worldAnchor,
            Vector3d plotAnchor, Quaterniond targetOrientation, double targetYaw, String reason) {
        static Evaluation fail(String reason) {
            return new Evaluation(null, null, null, null, 0.0, reason);
        }
    }

    private static final class PendingRemoval {
        final LevelState state;
        final UUID id;
        int attempts;

        PendingRemoval(LevelState state, UUID id) {
            this.state = state;
            this.id = id;
        }
    }

    private static final class LevelState implements SubLevelObserver {
        private final ServerLevel level;
        private final ServerSubLevelContainer container;
        private final Map<UUID, ActiveLock> active = new LinkedHashMap<>();
        private final Map<UUID, Integer> stable = new HashMap<>();
        private boolean retired;
        private int ticks;
        private int nextScan;
        private int lastFailureLog = -FAILURE_LOG_INTERVAL_TICKS;

        LevelState(ServerLevel level, ServerSubLevelContainer container) {
            this.level = level;
            this.container = container;
        }

        @Override
        public void onSubLevelAdded(SubLevel subLevel) {
            if (retired || !(subLevel instanceof ServerSubLevel server)) {
                return;
            }
            UUID id = server.getUniqueId();
            if (registry().get(id) == null) {
                return;
            }
            // A re-added UUID must not keep a stale joint from the previous body.
            releaseInternal(id, "readded");
            stable.put(id, 0);
        }

        @Override
        public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
            if (retired) {
                return;
            }
            UUID id = subLevel.getUniqueId();
            releaseInternal(id, reason.name().toLowerCase(Locale.ROOT));
            stable.remove(id);
            if (reason == SubLevelRemovalReason.UNLOADED) {
                markLifecycle(id, ChassisRegistry.Lifecycle.UNLOADED);
            }
        }

        void tick() {
            if (retired) {
                return;
            }
            ticks++;
            retryPendingRemoval();
            verifyActive();
            if (ticks >= nextScan) {
                nextScan = ticks + ChassisRailLockRules.CHECK_INTERVAL_TICKS;
                scan();
            }
        }

        private void verifyActive() {
            for (Map.Entry<UUID, ActiveLock> entry : List.copyOf(active.entrySet())) {
                UUID id = entry.getKey();
                if (!active.containsKey(id)) {
                    continue;
                }
                ActiveLock lock = entry.getValue();
                ChassisRegistry.Entry registered = registry().get(id);
                if (registered == null) {
                    releaseInternal(id, "unregistered");
                    continue;
                }
                ServerSubLevel sub = serverSubLevel(id);
                if (sub == null) {
                    releaseInternal(id, "unloaded");
                    markLifecycle(id, ChassisRegistry.Lifecycle.UNLOADED);
                    continue;
                }
                if (!valid(lock.joint())) {
                    releaseInternal(id, "joint_lost");
                    markLifecycle(id, ChassisRegistry.Lifecycle.REGISTERED);
                    continue;
                }
                Evaluation evaluation;
                try {
                    evaluation = evaluate(sub, registered);
                } catch (RuntimeException failure) {
                    releaseInternal(id, "evaluate_failed");
                    markLifecycle(id, ChassisRegistry.Lifecycle.REGISTERED);
                    logFailure("evaluate", failure);
                    continue;
                }
                if (evaluation.context() == null
                        || ChassisRailLockRules.verify(evaluation.context())
                                != ChassisRailLockRules.Decision.HOLD) {
                    releaseInternal(id, evaluation.reason());
                    boolean derailed = evaluation.context() != null && evaluation.context().derailed();
                    markLifecycle(id, derailed ? ChassisRegistry.Lifecycle.DERAILED
                            : ChassisRegistry.Lifecycle.REGISTERED);
                }
            }
        }

        private void scan() {
            ChassisRegistry registry = registry();
            Set<UUID> known = new HashSet<>();
            for (ChassisRegistry.Entry entry : registry.entries()) {
                known.add(entry.id());
                if (active.containsKey(entry.id())
                        || !ChassisRailLockRules.standardBlueprint(entry.blueprint())) {
                    continue;
                }
                ServerSubLevel sub = serverSubLevel(entry.id());
                if (sub == null) {
                    stable.remove(entry.id());
                    continue;
                }
                Evaluation evaluation;
                try {
                    evaluation = evaluate(sub, entry);
                } catch (RuntimeException failure) {
                    stable.put(entry.id(), 0);
                    logFailure("evaluate", failure);
                    continue;
                }
                if (evaluation.context() == null
                        || !ChassisRailLockRules.createEligible(evaluation.context())) {
                    stable.put(entry.id(), 0);
                    continue;
                }
                int checks = stable.merge(entry.id(), 1, Integer::sum);
                if (checks < ChassisRailLockRules.STABLE_CHECKS) {
                    continue;
                }
                stable.remove(entry.id());
                create(entry, sub, evaluation);
            }
            stable.keySet().removeIf(id -> !known.contains(id));
        }

        private void create(ChassisRegistry.Entry entry, ServerSubLevel sub, Evaluation evaluation) {
            try {
                GenericConstraintHandle joint = pipeline().addConstraint(null, sub,
                        new GenericConstraintConfiguration(evaluation.worldAnchor(),
                                evaluation.plotAnchor(), evaluation.targetOrientation(), IDENTITY,
                                LOCKED_AXES));
                if (joint == null || !joint.isValid()) {
                    logFailure("create_invalid_" + entry.id(), null);
                    return;
                }
                active.put(entry.id(), new ActiveLock(entry.id(), joint));
                // SimuRail's axle rail joints are rebuilt with the hard axis set while this
                // body lock is active. Registration is UUID-only and leaves all other vehicles
                // on their normal suspension/curve behavior.
                RailJointLocks.register(entry.id());
                markLifecycle(entry.id(), ChassisRegistry.Lifecycle.ACTIVE);
                LOGGER.info("[chassis-lock] locked {} blueprint={} deckY={} railZ={} yaw={} "
                                + "axes=LINEAR_X free, LINEAR_Y/Z + ANGULAR_X/Y/Z locked",
                        entry.id(), entry.blueprint(), evaluation.worldAnchor().y,
                        evaluation.worldAnchor().z, evaluation.targetYaw());
            } catch (RuntimeException failure) {
                logFailure("create", failure);
            }
        }

        private void releaseInternal(UUID id, String reason) {
            ActiveLock lock = active.remove(id);
            if (lock == null) {
                return;
            }
            RailJointLocks.unregister(id);
            removeJoint(lock.joint(), id);
            LOGGER.debug("[chassis-lock] released {} ({})", id, reason);
        }

        private void releaseAll(String reason) {
            retired = true;
            for (ActiveLock lock : List.copyOf(active.values())) {
                RailJointLocks.unregister(lock.id());
                removeJoint(lock.joint(), lock.id());
            }
            active.clear();
            stable.clear();
            // The body/scene is going away; do not keep retrying handles that belonged
            // to this retired level state.
            PENDING.entrySet().removeIf(entry -> entry.getValue().state == this);
        }

        private void removeJoint(GenericConstraintHandle joint, UUID id) {
            if (joint == null) {
                return;
            }
            try {
                joint.remove();
            } catch (RuntimeException ignored) {
                // The body may already be gone; still verify below and retry if needed.
            }
            if (stillValid(joint)) {
                PENDING.put(joint, new PendingRemoval(this, id));
            }
        }

        private void retryPendingRemoval() {
            for (Iterator<Map.Entry<GenericConstraintHandle, PendingRemoval>> iterator =
                    PENDING.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<GenericConstraintHandle, PendingRemoval> entry = iterator.next();
                PendingRemoval pending = entry.getValue();
                if (pending.state != this) {
                    continue;
                }
                GenericConstraintHandle joint = entry.getKey();
                try {
                    joint.remove();
                } catch (RuntimeException ignored) {
                    // Retry next tick.
                }
                if (!stillValid(joint)) {
                    iterator.remove();
                    continue;
                }
                pending.attempts++;
                if (pending.attempts == 20 || pending.attempts == MAX_REMOVE_ATTEMPTS) {
                    LOGGER.error("[chassis-lock] joint for {} still valid after {} remove attempts",
                            pending.id, pending.attempts);
                }
                if (pending.attempts >= MAX_REMOVE_ATTEMPTS) {
                    // Last resort: keep the server alive rather than retrying forever.
                    iterator.remove();
                }
            }
        }

        private Evaluation evaluate(ServerSubLevel sub, ChassisRegistry.Entry entry) {
            if (sub == null || sub.isRemoved()) {
                return Evaluation.fail("removed");
            }
            if (!ChassisRailLockRules.standardBlueprint(entry.blueprint())) {
                return Evaluation.fail("nonstandard_blueprint");
            }

            Pose3d pose;
            try {
                RigidBodyHandle handle = RigidBodyHandle.of(sub);
                if (handle == null || !handle.isValid()) {
                    return Evaluation.fail("no_body");
                }
                pose = pipeline().readPose(sub, new Pose3d());
            } catch (RuntimeException failure) {
                return Evaluation.fail("no_body");
            }
            if (!finite(pose)) {
                return Evaluation.fail("invalid_pose");
            }
            Vector3d com = centerOfMass(sub);
            if (com == null) {
                return Evaluation.fail("invalid_com");
            }

            Vec3 plotCenter = sub.getPlot().getCenterBlock().getCenter();
            Vector3d plotDeck = new Vector3d(plotCenter.x, plotCenter.y + 0.5, plotCenter.z);
            Vector3d worldDeck = ChassisPose.anchor(pose.orientation(), plotDeck, com,
                    pose.position(), new Vector3d());
            ChassisPose.Angles angles = ChassisPose.angles(pose.orientation());
            double targetYaw = ChassisRailLockRules.nearestXAlignedYaw(angles.yawDegrees());
            double yawError = ChassisRailLockRules.yawErrorDegrees(angles.yawDegrees());

            Set<BlockPos> expected = expectedBogeys(entry.blueprint(),
                    sub.getPlot().getCenterBlock());
            Set<BlockPos> actual = new HashSet<>();
            int tracked = 0;
            boolean derailed = false;
            boolean bogeysEnabled = true;
            for (var actor : sub.getPlot().getBlockEntityActors()) {
                if (!(actor instanceof PhysicsBogeyBlockEntity bogey)) {
                    continue;
                }
                actual.add(bogey.getBlockPos().immutable());
                if (bogey.hasTrack()) {
                    tracked++;
                }
                if (bogey.isDerailed()) {
                    derailed = true;
                }
                if (!bogey.getOptions().enabled) {
                    // A disabled bogey drops its rail joint; the car is no longer a
                    // standard all-wheels-on-rail chassis and must not get the body lock.
                    bogeysEnabled = false;
                }
            }
            boolean standardBogeys = !expected.isEmpty() && actual.equals(expected) && bogeysEnabled;
            int expectedCount = expected.size();

            double railY = entry.nominalHeight() - 2.0;
            TrackCheck centerTrack = checkTrack(level, worldDeck.x, railY, worldDeck.z);
            boolean allBogeysLoaded = true;
            boolean allLoadedBogeysStraight = true;
            for (BlockPos plotPos : expected) {
                Vector3d point = ChassisPose.anchor(pose.orientation(), blockCenter(plotPos), com,
                        pose.position(), new Vector3d());
                TrackCheck check = checkTrack(level, point.x, railY, point.z);
                if (!check.loaded()) {
                    allBogeysLoaded = false;
                } else if (!check.straight()) {
                    allLoadedBogeysStraight = false;
                }
            }

            boolean railKnown = centerTrack.loaded() && centerTrack.straight();
            double railZ = railKnown ? centerTrack.pos().getZ() + 0.5 : worldDeck.z;
            double lateralError = railKnown ? worldDeck.z - railZ : 0.0;
            ChassisRailLockRules.TrackState track = new ChassisRailLockRules.TrackState(
                    centerTrack.loaded(), centerTrack.straight(), allBogeysLoaded,
                    allLoadedBogeysStraight);
            ChassisRailLockRules.Context context = new ChassisRailLockRules.Context(
                    true, true, true, true, true, standardBogeys, expectedCount, tracked,
                    derailed, track, worldDeck.y, entry.nominalHeight(), lateralError, yawError,
                    Math.abs(angles.pitchDegrees()), Math.abs(angles.rollDegrees()));

            if (!standardBogeys) {
                return new Evaluation(context, null, null, null, targetYaw, "nonstandard_bogeys");
            }
            if (tracked != expectedCount) {
                return new Evaluation(context, null, null, null, targetYaw, "bogey_off_track");
            }
            if (derailed) {
                return new Evaluation(context, null, null, null, targetYaw, "derailed");
            }
            if (!centerTrack.loaded()) {
                return new Evaluation(context, null, null, null, targetYaw, "track_unloaded");
            }
            if (!centerTrack.straight()) {
                return new Evaluation(context, null, null, null, targetYaw, "track_not_xo");
            }
            if (!track.createReady()) {
                return new Evaluation(context, null, null, null, targetYaw, "bogey_track_not_xo");
            }

            Vector3d worldAnchor = new Vector3d(worldDeck.x, entry.nominalHeight(), railZ);
            Quaterniond targetOrientation = new Quaterniond()
                    .rotationY(Math.toRadians(targetYaw));
            return new Evaluation(context, worldAnchor, plotDeck, targetOrientation, targetYaw,
                    "ok");
        }

        private Set<BlockPos> expectedBogeys(ChassisBlueprint blueprint, BlockPos center) {
            Set<BlockPos> expected = new HashSet<>();
            for (ChassisBlueprint.Cell cell : blueprint.cells()) {
                if (cell.symbol() == 'W' || cell.symbol() == 'I') {
                    expected.add(center.offset(cell.x(), 0, cell.z()));
                }
            }
            return expected;
        }

        private TrackCheck checkTrack(ServerLevel level, double x, double y, double z) {
            BlockPos pos = BlockPos.containing(x, y, z);
            if (!level.isLoaded(pos)) {
                return new TrackCheck(false, false, pos);
            }
            BlockState state = level.getBlockState(pos);
            // A hidden bezier curve / track switch keeps HAS_BE=true even when the block
            // shape looks straight; such a block must not be treated as a straight X rail.
            boolean straight = state.getBlock() instanceof TrackBlock
                    && state.getValue(TrackBlock.SHAPE) == TrackShape.XO
                    && !state.getValue(TrackBlock.HAS_BE);
            return new TrackCheck(true, straight, pos);
        }

        private ServerSubLevel serverSubLevel(UUID id) {
            SubLevel subLevel = container.getSubLevel(id);
            return subLevel instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
        }

        private PhysicsPipeline pipeline() {
            return container.physicsSystem().getPipeline();
        }

        private ChassisRegistry registry() {
            return ChassisRegistryData.get(level).registry();
        }

        private void markLifecycle(UUID id, ChassisRegistry.Lifecycle lifecycle) {
            ChassisRegistryData data = ChassisRegistryData.get(level);
            ChassisRegistry.Entry entry = data.registry().get(id);
            if (entry == null || entry.lifecycle() == lifecycle) {
                return;
            }
            if (data.registry().mark(id, lifecycle)) {
                data.setDirty();
            }
        }

        private void logFailure(String action, RuntimeException failure) {
            if (ticks - lastFailureLog < FAILURE_LOG_INTERVAL_TICKS) {
                return;
            }
            lastFailureLog = ticks;
            if (failure == null) {
                LOGGER.warn("[chassis-lock] {} failed in {}", action, level.dimension().location());
            } else {
                LOGGER.warn("[chassis-lock] {} failed in {}", action, level.dimension().location(),
                        failure);
            }
        }
    }

    /** Single source of truth: the pure rule set decides the production locked axes. */
    private static Set<ConstraintJointAxis> constraintAxes(Set<ChassisRailLockRules.Axis> axes) {
        EnumSet<ConstraintJointAxis> mapped = EnumSet.noneOf(ConstraintJointAxis.class);
        for (ChassisRailLockRules.Axis axis : axes) {
            mapped.add(ConstraintJointAxis.valueOf(axis.name()));
        }
        return Collections.unmodifiableSet(mapped);
    }

    private static Vector3d blockCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static Vector3d centerOfMass(ServerSubLevel sub) {
        try {
            Vector3dc com = sub.getMassTracker().getCenterOfMass();
            if (com == null || !finite(com)) {
                return null;
            }
            return new Vector3d(com);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean finite(Pose3dc pose) {
        return pose != null && finite(pose.position()) && finite(pose.orientation());
    }

    private static boolean finite(Vector3dc vector) {
        return vector != null && Double.isFinite(vector.x()) && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static boolean finite(Quaterniondc quaternion) {
        if (quaternion == null || !Double.isFinite(quaternion.x()) || !Double.isFinite(quaternion.y())
                || !Double.isFinite(quaternion.z()) || !Double.isFinite(quaternion.w())) {
            return false;
        }
        double x = quaternion.x();
        double y = quaternion.y();
        double z = quaternion.z();
        double w = quaternion.w();
        return x * x + y * y + z * z + w * w > 1.0E-9;
    }

    private static boolean valid(GenericConstraintHandle joint) {
        try {
            return joint != null && joint.isValid();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 不确定的引擎查询按“仍存在”处理，避免把可能残留的约束静默丢掉。 */
    private static boolean stillValid(GenericConstraintHandle joint) {
        try {
            return joint != null && joint.isValid();
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private ChassisRailLock() {
    }
}

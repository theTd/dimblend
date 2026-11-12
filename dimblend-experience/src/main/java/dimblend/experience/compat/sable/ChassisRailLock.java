package dimblend.experience.compat.sable;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * {@code dimblend:rotating} 车架世界平面锁（{@code DECISIONS.md} #2，Option B）。
 *
 * <p><b>作用。</b>对 {@code dimblend:rotating} 内所有已挂轨且未脱轨的车体（含已登记标准车架，以及未登记但装有启用
 * {@link PhysicsBogeyBlockEntity} 的 {@link ServerSubLevel}），在 Sable/Rapier 中建立一个
 * {@code bodyA == null} 的世界 generic joint：锁 {@code LINEAR_Y}、{@code ANGULAR_X}、
 * {@code ANGULAR_Z}，保留 {@code LINEAR_X}、{@code LINEAR_Z}、{@code ANGULAR_Y} 自由。车体因此锁死标称甲板高度
 * （标准 {@code Y=66.0}）与零俯仰/零横滚，同时可正常通过直线、道岔、贝塞尔曲线与侧线并自由偏航。</p>
 *
 * <p><b>坐标与重锚。</b>已登记标准车架取 {@code (plotCenter.x, plotCenter.y + 0.5, plotCenter.z)} 与
 * {@code entry.nominalHeight()}；未登记车体取 {@code (plotCenter.x, bogeyPlotY + 1.0, plotCenter.z)}
 * （其中 {@code bogeyPlotY = bogey.getBlockPos().getY()}，与标准锚点层 {@code cy + 1.0} 完全一致）与
 * {@code 66.0}，且仅在运行期 {@code LevelState} 维护，不写入 {@link ChassisRegistryData}。
 * 当水平移动超过 32 格或偏航变化超过 15° 时，调用 {@code GenericConstraintHandle#setFrame1} 原地刷新世界锚点与偏航帧；
 * 不修改 {@code MassData}，也不做任何刚体瞬移。</p>
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

    private record ActiveLock(
            UUID id,
            GenericConstraintHandle joint,
            Vector3d frameAnchor,
            double frameYawDegrees) {
    }

    private record Evaluation(
            ChassisRailLockRules.Context context,
            Vector3d worldDeck,
            double nominalHeight,
            Vector3d worldAnchor,
            Vector3d plotAnchor,
            Quaterniond yawFrame,
            double yawDegrees,
            String reason) {
        static Evaluation fail(String reason) {
            return new Evaluation(null, null, 0.0, null, null, null, 0.0, reason);
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
            // A re-added UUID must not keep a stale joint from the previous body.
            releaseInternal(id, "readded");
            if (registry().get(id) != null || hasEnabledBogey(server)) {
                stable.put(id, 0);
            }
        }

        @Override
        public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
            if (retired) {
                return;
            }
            UUID id = subLevel.getUniqueId();
            releaseInternal(id, reason.name().toLowerCase(Locale.ROOT));
            stable.remove(id);
            if (reason == SubLevelRemovalReason.UNLOADED && registry().get(id) != null) {
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
            ChassisRegistry registry = registry();
            for (Map.Entry<UUID, ActiveLock> entry : List.copyOf(active.entrySet())) {
                UUID id = entry.getKey();
                if (!active.containsKey(id)) {
                    continue;
                }
                ActiveLock lock = entry.getValue();
                ChassisRegistry.Entry registered = registry.get(id);
                ServerSubLevel sub = serverSubLevel(id);
                if (sub == null) {
                    releaseInternal(id, "unloaded");
                    if (registered != null) {
                        markLifecycle(id, ChassisRegistry.Lifecycle.UNLOADED);
                    }
                    continue;
                }
                if (!valid(lock.joint())) {
                    releaseInternal(id, "joint_lost");
                    if (registered != null) {
                        markLifecycle(id, ChassisRegistry.Lifecycle.REGISTERED);
                    }
                    continue;
                }
                Evaluation evaluation;
                try {
                    evaluation = evaluate(sub, registered);
                } catch (RuntimeException failure) {
                    releaseInternal(id, "evaluate_failed");
                    if (registered != null) {
                        markLifecycle(id, ChassisRegistry.Lifecycle.REGISTERED);
                    }
                    logFailure("evaluate", failure);
                    continue;
                }
                if (evaluation.context() == null
                        || ChassisRailLockRules.verify(evaluation.context())
                                != ChassisRailLockRules.Decision.HOLD) {
                    releaseInternal(id, evaluation.reason());
                    if (registered != null) {
                        boolean derailed = evaluation.context() != null && evaluation.context().derailed();
                        markLifecycle(id, derailed
                                ? ChassisRegistry.Lifecycle.DERAILED
                                : ChassisRegistry.Lifecycle.REGISTERED);
                    }
                    continue;
                }
                double dx = evaluation.worldDeck().x - lock.frameAnchor().x;
                double dz = evaluation.worldDeck().z - lock.frameAnchor().z;
                if (ChassisRailLockRules.needsFrameRefresh(
                        dx, dz, evaluation.yawDegrees(), lock.frameYawDegrees())) {
                    try {
                        Vector3d refreshedAnchor = new Vector3d(
                                evaluation.worldDeck().x,
                                evaluation.nominalHeight(),
                                evaluation.worldDeck().z);
                        lock.joint().setFrame1(refreshedAnchor, evaluation.yawFrame());
                        active.put(id, new ActiveLock(
                                id, lock.joint(), refreshedAnchor, evaluation.yawDegrees()));
                    } catch (RuntimeException failure) {
                        releaseInternal(id, "refresh_frame_failed");
                        if (registered != null) {
                            markLifecycle(id, ChassisRegistry.Lifecycle.REGISTERED);
                        }
                        logFailure("refresh_frame", failure);
                    }
                }
            }
        }

        private void scan() {
            ChassisRegistry registry = registry();
            Set<UUID> seen = new HashSet<>();
            for (ChassisRegistry.Entry entry : registry.entries()) {
                UUID id = entry.id();
                seen.add(id);
                if (active.containsKey(id)) {
                    continue;
                }
                ServerSubLevel sub = serverSubLevel(id);
                if (sub == null) {
                    stable.remove(id);
                    continue;
                }
                scanCandidate(id, sub, entry);
            }
            for (ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                UUID id = sub.getUniqueId();
                if (!seen.add(id) || active.containsKey(id)) {
                    continue;
                }
                if (!hasEnabledBogey(sub)) {
                    stable.remove(id);
                    continue;
                }
                scanCandidate(id, sub, null);
            }
            stable.keySet().removeIf(id -> !seen.contains(id));
        }

        private void scanCandidate(
                UUID id, ServerSubLevel sub, @Nullable ChassisRegistry.Entry entry) {
            Evaluation evaluation;
            try {
                evaluation = evaluate(sub, entry);
            } catch (RuntimeException failure) {
                stable.put(id, 0);
                logFailure("evaluate", failure);
                return;
            }
            if (evaluation.context() == null
                    || !ChassisRailLockRules.createEligible(evaluation.context())) {
                stable.put(id, 0);
                return;
            }
            int checks = stable.merge(id, 1, Integer::sum);
            if (ChassisRailLockRules.decide(evaluation.context(), checks)
                    != ChassisRailLockRules.Decision.CREATE) {
                return;
            }
            stable.remove(id);
            create(id, entry, sub, evaluation);
        }

        private void create(
                UUID id,
                @Nullable ChassisRegistry.Entry entry,
                ServerSubLevel sub,
                Evaluation evaluation) {
            try {
                GenericConstraintHandle joint = pipeline().addConstraint(null, sub,
                        new GenericConstraintConfiguration(
                                evaluation.worldAnchor(),
                                evaluation.plotAnchor(),
                                evaluation.yawFrame(),
                                IDENTITY,
                                LOCKED_AXES));
                if (joint == null || !joint.isValid()) {
                    logFailure("create_invalid_" + id, null);
                    return;
                }
                active.put(id, new ActiveLock(
                        id,
                        joint,
                        new Vector3d(evaluation.worldAnchor()),
                        evaluation.yawDegrees()));
                if (entry != null) {
                    markLifecycle(id, ChassisRegistry.Lifecycle.ACTIVE);
                }
                LOGGER.info("[chassis-lock] locked {} blueprint={} deckY={} nominalY={} yaw={} "
                                + "axes=LINEAR_X/Z + ANGULAR_Y free, LINEAR_Y + ANGULAR_X/Z locked",
                        id,
                        entry != null ? entry.blueprint() : "UNREGISTERED",
                        evaluation.worldDeck().y,
                        evaluation.nominalHeight(),
                        evaluation.yawDegrees());
            } catch (RuntimeException failure) {
                logFailure("create", failure);
            }
        }

        private void releaseInternal(UUID id, String reason) {
            ActiveLock lock = active.remove(id);
            if (lock == null) {
                return;
            }
            removeJoint(lock.joint(), id);
            LOGGER.debug("[chassis-lock] released {} ({})", id, reason);
        }

        private void releaseAll(String reason) {
            retired = true;
            for (ActiveLock lock : List.copyOf(active.values())) {
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

        private Evaluation evaluate(ServerSubLevel sub, @Nullable ChassisRegistry.Entry entry) {
            if (sub == null || sub.isRemoved()) {
                return Evaluation.fail("removed");
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

            BlockPos plotCenterBlock = sub.getPlot().getCenterBlock();
            Vec3 plotCenter = plotCenterBlock.getCenter();
            double nominalHeight;
            Vector3d plotDeck;
            Set<BlockPos> bogeyPositions;
            int expectedCount;
            int tracked = 0;
            boolean derailed = false;
            boolean bogeyLayoutValid = true;

            if (entry != null && ChassisRailLockRules.standardBlueprint(entry.blueprint())) {
                nominalHeight = entry.nominalHeight();
                // plotCenter.y == plotCenterBlock.getY() + 0.5, so plotCenter.y + 0.5 == cy + 1.0.
                plotDeck = new Vector3d(plotCenter.x, plotCenter.y + 0.5, plotCenter.z);
                Set<BlockPos> expected = expectedBogeys(entry.blueprint(), plotCenterBlock);
                Set<BlockPos> actual = new HashSet<>();
                boolean allEnabled = true;
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
                        allEnabled = false;
                    }
                }
                bogeyLayoutValid = !expected.isEmpty() && actual.equals(expected) && allEnabled;
                bogeyPositions = expected;
                expectedCount = expected.size();
            } else {
                nominalHeight = entry != null
                        ? entry.nominalHeight()
                        : ChassisRailLockRules.DEFAULT_NOMINAL_DECK_HEIGHT;
                Set<BlockPos> enabledPositions = new HashSet<>();
                Integer bogeyPlotY = null;
                boolean consistentY = true;
                for (var actor : sub.getPlot().getBlockEntityActors()) {
                    if (!(actor instanceof PhysicsBogeyBlockEntity bogey)
                            || !bogey.getOptions().enabled) {
                        continue;
                    }
                    BlockPos pos = bogey.getBlockPos().immutable();
                    enabledPositions.add(pos);
                    if (bogeyPlotY == null) {
                        bogeyPlotY = pos.getY();
                    } else if (bogeyPlotY != pos.getY()) {
                        consistentY = false;
                    }
                    if (bogey.hasTrack()) {
                        tracked++;
                    }
                    if (bogey.isDerailed()) {
                        derailed = true;
                    }
                }
                if (enabledPositions.isEmpty()) {
                    return Evaluation.fail("no_enabled_bogeys");
                }
                if (!consistentY) {
                    return Evaluation.fail("inconsistent_bogey_y");
                }
                bogeyPositions = enabledPositions;
                expectedCount = enabledPositions.size();
                // bogeyPlotY is integer BlockPos#getY(), so bogeyPlotY + 1.0 matches plotCenter.y + 0.5.
                plotDeck = new Vector3d(plotCenter.x, bogeyPlotY + 1.0, plotCenter.z);
            }

            Vector3d worldDeck = ChassisPose.anchor(pose.orientation(), plotDeck, com,
                    pose.position(), new Vector3d());
            ChassisPose.Angles angles = ChassisPose.angles(pose.orientation());

            double railY = nominalHeight - 2.0;
            boolean centerChunkReady = chunkReady(
                    level, BlockPos.containing(worldDeck.x, railY, worldDeck.z));
            boolean bogeyChunksReady = true;
            for (BlockPos plotPos : bogeyPositions) {
                Vector3d point = ChassisPose.anchor(pose.orientation(), blockCenter(plotPos), com,
                        pose.position(), new Vector3d());
                if (!chunkReady(level, BlockPos.containing(point.x, railY, point.z))) {
                    bogeyChunksReady = false;
                    break;
                }
            }

            int effectiveTracked = bogeyLayoutValid ? tracked : 0;
            ChassisRailLockRules.TrackState track = new ChassisRailLockRules.TrackState(
                    centerChunkReady, bogeyChunksReady);
            ChassisRailLockRules.Context context = new ChassisRailLockRules.Context(
                    ChassisEvents.inDimension(level),
                    true,
                    true,
                    expectedCount,
                    effectiveTracked,
                    derailed,
                    track,
                    worldDeck.y,
                    nominalHeight,
                    angles.pitchDegrees(),
                    angles.rollDegrees());

            if (!bogeyLayoutValid) {
                return new Evaluation(context, worldDeck, nominalHeight, null, null, null,
                        angles.yawDegrees(), "nonstandard_bogeys");
            }
            if (tracked != expectedCount) {
                return new Evaluation(context, worldDeck, nominalHeight, null, null, null,
                        angles.yawDegrees(), "bogey_off_track");
            }
            if (derailed) {
                return new Evaluation(context, worldDeck, nominalHeight, null, null, null,
                        angles.yawDegrees(), "derailed");
            }
            if (!track.chunksReady()) {
                return new Evaluation(context, worldDeck, nominalHeight, null, null, null,
                        angles.yawDegrees(), "chunk_not_ready");
            }

            Vector3d worldAnchor = new Vector3d(worldDeck.x, nominalHeight, worldDeck.z);
            Quaterniond yawFrame = ChassisPose.yawFrame(pose.orientation());
            return new Evaluation(context, worldDeck, nominalHeight, worldAnchor, plotDeck,
                    yawFrame, angles.yawDegrees(), "ok");
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

        private static boolean hasEnabledBogey(ServerSubLevel sub) {
            for (var actor : sub.getPlot().getBlockEntityActors()) {
                if (actor instanceof PhysicsBogeyBlockEntity bogey && bogey.getOptions().enabled) {
                    return true;
                }
            }
            return false;
        }

        private static boolean chunkReady(ServerLevel level, BlockPos pos) {
            return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
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

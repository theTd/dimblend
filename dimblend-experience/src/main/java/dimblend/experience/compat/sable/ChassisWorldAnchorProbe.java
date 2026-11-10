package dimblend.experience.compat.sable;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.DimBlend;
import dimblend.experience.chassis.ChassisPose;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 阶段 1 的 Rapier 世界锚探针：在测试存档里对<b>指定的一台</b> Sable 车架建一个短时的
 * 世界锚 joint，记录发动机真姿态、plot/world anchor、轮 pivot 高度和句柄生命周期，然后
 * 无条件销毁。它<b>不接入生产自动 tick</b>，只在 {@code /dbx chassis probe start} 或
 * GameTest 直接调用时工作。
 *
 * <p><b>为什么不是只读接口。</b> Rapier 的 null 端在 Java 层是 {@code bodyA == null}，
 * {@code RapierPhysicsPipeline#addConstraint} 把它交给
 * {@code RapierGenericConstraintHandle.create}，最终在
 * {@code sable_rapier/src/main/rust/rapier/src/joints.rs} 里映射为
 * {@code scene.ground_handle.unwrap()}（joints.rs:633-644）。joint 的锁定轴是
 * <b>关节帧轴</b>：{@code GenericJointBuilder::new(locked_axes)} 只在
 * {@code local_frame1.rotation}/{@code local_frame2.rotation} 上设置旋转（joints.rs:660-665）。
 * 所以探针必须用真实车体姿态算出世界锚和关节帧，不能把 {@code ConstraintJointAxis} 当世界轴
 * 直接读。</p>
 *
 * <p><b>模式。</b>
 * <ul>
 *   <li>{@code read}：不建 joint，只采样真实姿态和世界锚；</li>
 *   <li>{@code free}：建一个无锁定轴的 generic joint，验证 null→ground 映射和帧方向，
 *       不约束运动；</li>
 *   <li>{@code hold}：建一个锁 {@code LINEAR_Y, ANGULAR_X, ANGULAR_Z} 的 generic joint，
 *       即“锁垂向/pitch/roll、保留 xz/yaw”的候选帧，用短时漂移验证世界锚。</li>
 * </ul>
 * `free`/`hold` 在停用、超时、目标移除、句柄失效或服务器停止时销毁 joint；只有
 * {@code isValid()} 为 false 才记 removed，仍为真则进入每 tick 重试队列，日志指数退避，
 * 绝不静默留下世界约束。</p>
 *
 * <p><b>限频。</b>目标只解析一次（命令或 GameTest 传入），每 {@value #SAMPLE_INTERVAL_TICKS}
 * tick 采样一次，不做全局每 tick 扫描。</p>
 *
 * <p><b>锚点不是质心。</b>plot center 是装配 anchor，不是质心；`hold` 关节的 joint impulses
 * 含力臂，只能当力臂观测，不能当纯力；{@code verticalError}/角度漂移才是锁有效性的判据。</p>
 *
 * <p><b>轮 pivot。</b>Simurail 的 {@code PhysicsBogeyBlockEntity#pivotPose} 是 protected，
 * 没有公开 getter；探针用一次反射读取这个诊断字段，失败时回退到转向架方块中心的世界投影，
 * 并在 {@code source} 里写清是 {@code pivotPose} 还是 {@code blockCenter}。这条路径不进入
 * 生产逻辑。</p>
 */
public final class ChassisWorldAnchorProbe {
    private static final org.slf4j.Logger LOGGER = DimBlend.LOGGER;
    private static final int SAMPLE_INTERVAL_TICKS = 10;
    private static final int MAX_SAMPLES = 240;
    private static final int MAX_DUMP_SAMPLES = 40;
    private static final double NEAREST_RADIUS = 32.0;
    private static final int MIN_DURATION_TICKS = 20;
    private static final int MAX_DURATION_TICKS = 1200;

    private static final Field PIVOT_POSE = findPivotPose();
    private static final Map<GenericConstraintHandle, PendingRemoval> PENDING_REMOVAL = new LinkedHashMap<>();
    private static Session active;
    private static Report last;
    private static String lastFailure = "none";

    public enum Mode {
        READ,
        FREE,
        HOLD;

        static Mode parse(String name) {
            for (Mode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return null;
        }
    }

    public record WheelPivot(BlockPos plotPos, double x, double y, double z, String source,
                             boolean hasTrack, boolean derailed) {
    }

    public record Sample(int tick, boolean handleValid,
                         double height, double yawDegrees, double pitchDegrees, double rollDegrees,
                         double logicalHeight,
                         double comX, double comY, double comZ,
                         double anchorX, double anchorY, double anchorZ,
                         double anchorDrift, double verticalError,
                         boolean jointValid, double jointLinearImpulse, double jointAngularImpulse,
                         List<WheelPivot> wheelPivots) {
    }

    public record Report(UUID target, int bodyRuntimeId, String mode, int startedTick, int stoppedTick,
                         String stopReason, int sampleCount, boolean jointCreated,
                         boolean jointRemoveAttempted, boolean jointRemoved,
                         boolean jointValidAfterRemove, String error,
                         String plotAnchor, String centerOfMass, String worldAnchor,
                         String worldFrame, String lockedAxes,
                         List<Sample> samples) {
    }

    private static final class PendingRemoval {
        final Session session;
        int attempts;
        int nextLogAttempt = 2;

        PendingRemoval(Session session) {
            this.session = session;
        }
    }

    private static final class Session {
        final MinecraftServer server;
        final ServerLevel level;
        final ServerSubLevel target;
        final Mode mode;
        final int startedTick;
        final int durationTicks;
        final PhysicsPipeline pipeline;
        final Vector3d plotAnchor;
        final Vector3d centerOfMass;
        final Vector3d worldAnchor;
        final Quaterniond worldFrame;
        final Quaterniond bodyFrame;
        final Set<ConstraintJointAxis> lockedAxes;
        final List<Sample> samples = new ArrayList<>();
        GenericConstraintHandle joint;
        boolean jointCreated;
        boolean jointRemoveAttempted;
        boolean jointRemoved;
        boolean jointValidAfterRemove;
        String error;
        Report stoppedReport;
        int lastSampleTick = Integer.MIN_VALUE;

        Session(MinecraftServer server, ServerLevel level, ServerSubLevel target, Mode mode, int startedTick,
                int durationTicks, PhysicsPipeline pipeline, Vector3d plotAnchor, Vector3d centerOfMass,
                Vector3d worldAnchor, Quaterniond worldFrame, Quaterniond bodyFrame,
                Set<ConstraintJointAxis> lockedAxes) {
            this.server = server;
            this.level = level;
            this.target = target;
            this.mode = mode;
            this.startedTick = startedTick;
            this.durationTicks = durationTicks;
            this.pipeline = pipeline;
            this.plotAnchor = new Vector3d(plotAnchor);
            this.centerOfMass = new Vector3d(centerOfMass);
            this.worldAnchor = new Vector3d(worldAnchor);
            this.worldFrame = new Quaterniond(worldFrame);
            this.bodyFrame = new Quaterniond(bodyFrame);
            this.lockedAxes = Set.copyOf(lockedAxes);
        }

        Report report(int now, String reason) {
            return new Report(target.getUniqueId(), target.getRuntimeId(), mode.name(), startedTick, now,
                    reason, samples.size(), jointCreated, jointRemoveAttempted, jointRemoved,
                    jointValidAfterRemove, error,
                    describe(plotAnchor), describe(centerOfMass), describe(worldAnchor),
                    describe(worldFrame), lockedAxes.toString(), List.copyOf(samples));
        }
    }

    /** 命令入口：解析 {@code <uuid|nearest>}、模式与时长，并给人读反馈。 */
    public static boolean start(CommandSourceStack source, String targetName, String modeName, int durationTicks) {
        if (!ModList.get().isLoaded("sable") || !ModList.get().isLoaded("simurail")) {
            source.sendFailure(Component.literal("底盘世界锚探针需要 Sable 与 Simurail。"));
            return false;
        }
        Mode mode = Mode.parse(modeName);
        if (mode == null) {
            source.sendFailure(Component.literal("模式必须是 read、free 或 hold。"));
            return false;
        }
        ServerLevel level = source.getLevel();
        ServerSubLevel target = resolve(level, source.getPosition(), targetName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到目标车架：" + targetName
                    + "（用指定 UUID，或 nearest 取 " + (int) NEAREST_RADIUS + " 格内最近的一台）"));
            return false;
        }
        if (!start(level, target, mode, durationTicks)) {
            source.sendFailure(Component.literal("探针启动失败：" + failure()));
            return false;
        }
        source.sendSuccess(() -> Component.literal("底盘世界锚探针已启动：target=" + target.getUniqueId()
                + " mode=" + mode + " duration=" + (durationTicks / 20) + "s；"
                + "/dbx chassis probe status | dump | stop"), false);
        return true;
    }

    /** GameTest/命令共用入口：目标已经解析好。 */
    public static boolean start(ServerLevel level, ServerSubLevel target, Mode mode, int durationTicks) {
        stop("replaced");
        lastFailure = "none";
        if (level == null || target == null || target.isRemoved()) {
            return fail("invalid_target");
        }
        if (target.getLevel() != level) {
            return fail("target_level_mismatch");
        }
        int duration = Math.max(MIN_DURATION_TICKS, Math.min(MAX_DURATION_TICKS, durationTicks));
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return fail("no_sublevel_container");
        }
        RigidBodyHandle handle = RigidBodyHandle.of(target);
        if (handle == null || !handle.isValid()) {
            return fail("no_rigid_body");
        }
        PhysicsPipeline pipeline;
        try {
            pipeline = container.physicsSystem().getPipeline();
        } catch (RuntimeException failure) {
            return fail("no_pipeline: " + failure);
        }
        if (pipeline == null) {
            return fail("no_pipeline");
        }
        Pose3d enginePose;
        try {
            enginePose = pipeline.readPose(target, new Pose3d());
        } catch (RuntimeException failure) {
            return fail("read_pose: " + failure);
        }
        if (!finite(enginePose)) {
            return fail("invalid_pose");
        }
        Pose3dc logicalPose = target.logicalPose();
        if (!finite(logicalPose) || logicalPose.position().distanceSquared(enginePose.position()) > 256.0) {
            return fail("pose_mismatch");
        }
        Vector3d com = centerOfMass(target);
        if (com == null) {
            return fail("invalid_com");
        }
        // 装配 anchor，不是质心；world = bodyPos + R*(plotPoint - COM)，只减一次 COM。
        Vec3 center = target.getPlot().getCenterBlock().getCenter();
        Vector3d plotAnchor = new Vector3d(center.x, center.y, center.z);
        Vector3d worldAnchor = ChassisPose.anchor(enginePose.orientation(), plotAnchor, com,
                enginePose.position(), new Vector3d());
        Quaterniond worldFrame = ChassisPose.yawFrame(enginePose.orientation());
        Quaterniond bodyFrame = ChassisPose.bodyFrame(enginePose.orientation(), worldFrame);
        Set<ConstraintJointAxis> lockedAxes = axes(mode);
        int now = level.getServer().getTickCount();
        Session session = new Session(level.getServer(), level, target, mode, now, duration, pipeline,
                plotAnchor, com, worldAnchor, worldFrame, bodyFrame, lockedAxes);

        if (mode != Mode.READ) {
            try {
                GenericConstraintHandle joint = pipeline.addConstraint(null, target,
                        new GenericConstraintConfiguration(worldAnchor, plotAnchor, worldFrame, bodyFrame, lockedAxes));
                if (joint == null || !joint.isValid()) {
                    session.error = "add_returned_invalid";
                    last = session.report(now, "add_failed");
                    LOGGER.warn("[chassis-probe] world anchor joint was not created for " + target.getUniqueId());
                    return false;
                }
                session.joint = joint;
                session.jointCreated = true;
            } catch (RuntimeException failure) {
                session.error = "add_failed: " + failure;
                last = session.report(now, "add_failed");
                LOGGER.warn("[chassis-probe] world anchor joint failed for " + target.getUniqueId(), failure);
                return false;
            }
        }

        active = session;
        LOGGER.info("[chassis-probe] start target=" + target.getUniqueId()
                + " mode=" + mode + " plotAnchor=" + describe(plotAnchor)
                + " com=" + describe(com) + " worldAnchor=" + describe(worldAnchor)
                + " worldFrame=" + describe(worldFrame) + " axes=" + lockedAxes);
        try {
            sample(session, true);
        } catch (RuntimeException failure) {
            session.error = "sample_error: " + failure;
            stop("sample_error");
            return false;
        }
        return true;
    }

    /** 每服务器 tick 调用；没有活动会话时只重试待销毁句柄。 */
    public static void tick(MinecraftServer server) {
        retryPendingRemoval();
        Session session = active;
        if (session == null) {
            return;
        }
        if (server != session.server) {
            stop("server_replaced");
            return;
        }
        if (session.target.isRemoved()) {
            stop("target_removed");
            return;
        }
        if (server.getTickCount() - session.startedTick >= session.durationTicks) {
            stop("duration");
            return;
        }
        if (session.joint != null && !valid(session.joint)) {
            stop("joint_lost");
            return;
        }
        if (server.getTickCount() - session.lastSampleTick >= SAMPLE_INTERVAL_TICKS) {
            try {
                sample(session, false);
            } catch (RuntimeException failure) {
                session.error = "sample_error: " + failure;
                stop("sample_error");
            }
        }
    }

    public static void stop(String reason) {
        Session session = active;
        if (session == null) {
            return;
        }
        active = null;
        int now = session.server.getTickCount();
        destroy(session);
        last = session.report(now, reason);
        session.stoppedReport = last;
        LOGGER.info("[chassis-probe] stop reason=" + reason
                + " target=" + session.target.getUniqueId() + " samples=" + session.samples.size()
                + " jointCreated=" + session.jointCreated
                + " jointRemoveAttempted=" + session.jointRemoveAttempted
                + " jointRemoved=" + session.jointRemoved
                + " validAfterRemove=" + session.jointValidAfterRemove
                + (session.error == null ? "" : " error=" + session.error));
    }

    public static void shutdown() {
        stop("server_stopping");
        retryPendingRemoval();
        PENDING_REMOVAL.clear();
    }

    public static boolean isActive() {
        return active != null;
    }

    public static boolean activeJointValid() {
        Session session = active;
        return session != null && session.joint != null && valid(session.joint);
    }

    public static Report lastReport() {
        return last;
    }

    public static String failure() {
        if (last != null && last.error() != null && !last.error().isEmpty()) {
            return last.error();
        }
        return lastFailure;
    }

    public static String status() {
        Session session = active;
        Report report = session != null ? session.report(session.server.getTickCount(), "running") : last;
        if (report == null) {
            return "底盘世界锚探针：无记录"
                    + ("none".equals(lastFailure) ? "" : "（上次启动失败：" + lastFailure + "）");
        }
        StringBuilder out = new StringBuilder("底盘世界锚探针 ").append(report.mode())
                .append(" target=").append(report.target())
                .append(" bodyRuntimeId=").append(report.bodyRuntimeId())
                .append(" samples=").append(report.sampleCount())
                .append(" jointCreated=").append(report.jointCreated())
                .append(" jointRemoveAttempted=").append(report.jointRemoveAttempted())
                .append(" jointRemoved=").append(report.jointRemoved())
                .append(" validAfterRemove=").append(report.jointValidAfterRemove())
                .append(" com=").append(report.centerOfMass())
                .append(" reason=").append(report.stopReason());
        if (report.error() != null) {
            out.append(" error=").append(report.error());
        }
        if (!report.samples().isEmpty()) {
            out.append(" last=").append(formatSample(report.samples().get(report.samples().size() - 1)));
        }
        return out.toString();
    }

    public static List<String> dump() {
        Session session = active;
        Report report = session != null ? session.report(session.server.getTickCount(), "running") : last;
        if (report == null) {
            return List.of("底盘世界锚探针：无记录");
        }
        List<String> lines = new ArrayList<>();
        lines.add("mode=" + report.mode() + " target=" + report.target()
                + " bodyRuntimeId=" + report.bodyRuntimeId()
                + " plotAnchor=" + report.plotAnchor() + " com=" + report.centerOfMass()
                + " worldAnchor=" + report.worldAnchor()
                + " worldFrame=" + report.worldFrame() + " axes=" + report.lockedAxes()
                + " jointCreated=" + report.jointCreated()
                + " jointRemoveAttempted=" + report.jointRemoveAttempted()
                + " jointRemoved=" + report.jointRemoved()
                + " validAfterRemove=" + report.jointValidAfterRemove()
                + (report.error() == null ? "" : " error=" + report.error()));
        int from = Math.max(0, report.samples().size() - MAX_DUMP_SAMPLES);
        for (int index = from; index < report.samples().size(); index++) {
            lines.add(formatSample(report.samples().get(index)));
        }
        return lines;
    }

    private static void sample(Session session, boolean force) {
        int now = session.server.getTickCount();
        if (!force && session.lastSampleTick != Integer.MIN_VALUE
                && now - session.lastSampleTick < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        session.lastSampleTick = now;

        boolean handleValid = false;
        Pose3dc enginePose = null;
        try {
            RigidBodyHandle handle = RigidBodyHandle.of(session.target);
            handleValid = handle != null && handle.isValid();
            if (handleValid) {
                enginePose = session.pipeline.readPose(session.target, new Pose3d());
            }
        } catch (RuntimeException failure) {
            session.error = "read_pose: " + failure;
        }
        Pose3dc logicalPose = session.target.logicalPose();
        Pose3dc pose = enginePose != null ? enginePose : logicalPose;
        if (pose == null || !finite(pose)) {
            throw new IllegalStateException("invalid pose while sampling");
        }
        Vector3d com = centerOfMass(session.target);
        if (com == null) {
            throw new IllegalStateException("invalid_com while sampling");
        }

        ChassisPose.Angles angles = ChassisPose.angles(pose.orientation());
        Vector3d anchorNow = ChassisPose.anchor(pose.orientation(), session.plotAnchor, com,
                pose.position(), new Vector3d());
        double drift = anchorNow.distance(session.worldAnchor);
        double verticalError = anchorNow.y - session.worldAnchor.y;

        boolean jointValid = session.joint != null && valid(session.joint);
        Vector3d linearImpulse = new Vector3d();
        Vector3d angularImpulse = new Vector3d();
        if (jointValid) {
            try {
                session.joint.getJointImpulses(linearImpulse, angularImpulse);
            } catch (RuntimeException failure) {
                session.error = "joint_impulses: " + failure;
            }
        }

        Sample sample = new Sample(now, handleValid, pose.position().y(),
                angles.yawDegrees(), angles.pitchDegrees(), angles.rollDegrees(),
                logicalPose.position().y(),
                com.x(), com.y(), com.z(),
                anchorNow.x, anchorNow.y, anchorNow.z, drift, verticalError,
                jointValid, linearImpulse.length(), angularImpulse.length(),
                wheelPivots(session));
        session.samples.add(sample);
        if (session.samples.size() > MAX_SAMPLES) {
            session.samples.remove(0);
        }
        LOGGER.info("[chassis-probe] t=" + now + " mode=" + session.mode
                + " handle=" + handleValid + " y=" + f(pose.position().y())
                + " yaw=" + f2(angles.yawDegrees()) + " pitch=" + f2(angles.pitchDegrees())
                + " roll=" + f2(angles.rollDegrees()) + " com=" + describe(com)
                + " drift=" + f4(drift) + " dy=" + f4(verticalError) + " joint=" + jointValid
                + " wheels=" + sample.wheelPivots().size());
    }

    private static List<WheelPivot> wheelPivots(Session session) {
        List<WheelPivot> pivots = new ArrayList<>();
        for (var actor : session.target.getPlot().getBlockEntityActors()) {
            if (!(actor instanceof PhysicsBogeyBlockEntity bogey)) {
                continue;
            }
            try {
                pivots.add(pivot(session.level, bogey));
            } catch (RuntimeException failure) {
                session.error = append(session.error, "wheel_pivot: " + failure);
            }
        }
        return List.copyOf(pivots);
    }

    private static WheelPivot pivot(ServerLevel level, PhysicsBogeyBlockEntity bogey) {
        BlockPos plotPos = bogey.getBlockPos();
        if (PIVOT_POSE != null) {
            try {
                Pose3d pose = (Pose3d) PIVOT_POSE.get(bogey);
                if (pose != null && finite(pose.position())) {
                    return new WheelPivot(plotPos, pose.position().x(), pose.position().y(), pose.position().z(),
                            "pivotPose", bogey.hasTrack(), bogey.isDerailed());
                }
            } catch (IllegalAccessException ignored) {
                // Fall through to the block-centre projection.
            }
        }
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(plotPos));
        return new WheelPivot(plotPos, world.x, world.y, world.z,
                "blockCenter", bogey.hasTrack(), bogey.isDerailed());
    }

    private static Field findPivotPose() {
        try {
            Field field = PhysicsBogeyBlockEntity.class.getDeclaredField("pivotPose");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOGGER.warn("[chassis-probe] simurail pivotPose is not readable; wheel heights fall back to block centres: "
                            + failure);
            return null;
        }
    }

    private static void destroy(Session session) {
        if (session.joint == null) {
            return;
        }
        session.jointRemoveAttempted = true;
        try {
            session.joint.remove();
        } catch (RuntimeException failure) {
            session.error = append(session.error, "remove_failed: " + failure);
        }
        session.jointRemoved = !stillValid(session.joint);
        session.jointValidAfterRemove = !session.jointRemoved;
        if (!session.jointRemoved) {
            PENDING_REMOVAL.put(session.joint, new PendingRemoval(session));
            LOGGER.error("[chassis-probe] world anchor joint is still valid after remove attempt for "
                    + session.target.getUniqueId() + "; retrying with exponential log backoff");
        }
    }

    private static void retryPendingRemoval() {
        if (PENDING_REMOVAL.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<GenericConstraintHandle, PendingRemoval>> iterator =
                PENDING_REMOVAL.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<GenericConstraintHandle, PendingRemoval> entry = iterator.next();
            GenericConstraintHandle joint = entry.getKey();
            PendingRemoval pending = entry.getValue();
            if (!stillValid(joint)) {
                completeRemoval(pending);
                iterator.remove();
                continue;
            }
            try {
                joint.remove();
            } catch (RuntimeException failure) {
                if (pending.attempts == 0) {
                    pending.session.error = append(pending.session.error, "retry_remove_failed: " + failure);
                }
            }
            if (!stillValid(joint)) {
                completeRemoval(pending);
                iterator.remove();
                continue;
            }
            pending.attempts++;
            if (pending.attempts >= pending.nextLogAttempt) {
                LOGGER.error("[chassis-probe] world anchor joint still present after "
                        + pending.attempts + " removal attempts for "
                        + pending.session.target.getUniqueId());
                pending.nextLogAttempt = nextLogAttempt(pending.nextLogAttempt);
            }
        }
    }

    /** Removal is only "removed" once the engine reports the handle invalid. */
    private static void completeRemoval(PendingRemoval pending) {
        Session session = pending.session;
        session.jointRemoved = true;
        session.jointValidAfterRemove = false;
        if (last != null && last == session.stoppedReport) {
            last = session.report(session.server.getTickCount(), last.stopReason());
            session.stoppedReport = last;
        }
        LOGGER.info("[chassis-probe] world anchor joint removed after " + pending.attempts
                + " retries for " + session.target.getUniqueId());
    }

    /** 2, 4, 8, … 1024 ticks, then one ERROR per 1200 ticks (60 s) instead of every 10 s. */
    private static int nextLogAttempt(int current) {
        if (current >= 1200) {
            return 1200;
        }
        return Math.min(1200, current * 2);
    }

    private static Set<ConstraintJointAxis> axes(Mode mode) {
        return switch (mode) {
            case READ, FREE -> EnumSet.noneOf(ConstraintJointAxis.class);
            case HOLD -> EnumSet.of(ConstraintJointAxis.LINEAR_Y,
                    ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Z);
        };
    }

    private static ServerSubLevel resolve(ServerLevel level, Vec3 center, String name) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        if ("nearest".equalsIgnoreCase(name)) {
            return nearest(container, center);
        }
        UUID id;
        try {
            id = UUID.fromString(name);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (!sub.isRemoved() && sub.getUniqueId().equals(id)) {
                return sub;
            }
        }
        return null;
    }

    private static ServerSubLevel nearest(ServerSubLevelContainer container, Vec3 center) {
        Vector3d centerVec = new Vector3d(center.x, center.y, center.z);
        double bestDistance = NEAREST_RADIUS * NEAREST_RADIUS;
        ServerSubLevel best = null;
        for (ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved()) {
                continue;
            }
            double distance = sub.logicalPose().position().distanceSquared(centerVec);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = sub;
            }
        }
        return best;
    }

    private static boolean fail(String reason) {
        last = null;
        lastFailure = reason;
        LOGGER.warn("[chassis-probe] start refused: " + reason);
        return false;
    }

    private static boolean valid(GenericConstraintHandle joint) {
        try {
            return joint != null && joint.isValid();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** On an uncertain engine query, keep tracking the handle rather than declaring it removed. */
    private static boolean stillValid(GenericConstraintHandle joint) {
        try {
            return joint != null && joint.isValid();
        } catch (RuntimeException failure) {
            return true;
        }
    }

    /** COM 是 native body-local anchor 的必需项；null/非有限时 fail closed，绝不猜零。 */
    private static Vector3d centerOfMass(ServerSubLevel target) {
        try {
            var com = target.getMassTracker().getCenterOfMass();
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

    private static String append(String current, String next) {
        return current == null ? next : current + "; " + next;
    }

    private static String describe(Vector3dc vector) {
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vector.x(), vector.y(), vector.z());
    }

    private static String describe(Quaterniondc quaternion) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f",
                quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
    }

    private static String formatSample(Sample sample) {
        StringBuilder wheels = new StringBuilder();
        for (WheelPivot pivot : sample.wheelPivots()) {
            if (wheels.length() > 0) {
                wheels.append(';');
            }
            wheels.append(pivot.plotPos().toShortString()).append('=')
                    .append(f2(pivot.x())).append(',').append(f2(pivot.y())).append(',').append(f2(pivot.z()))
                    .append('/').append(pivot.source()).append(pivot.hasTrack() ? "/track" : "/off")
                    .append(pivot.derailed() ? "/derailed" : "");
        }
        return "t=" + sample.tick() + " handle=" + sample.handleValid()
                + " y=" + f(sample.height())
                + " yaw=" + f2(sample.yawDegrees()) + " pitch=" + f2(sample.pitchDegrees())
                + " roll=" + f2(sample.rollDegrees()) + " logicalY=" + f(sample.logicalHeight())
                + " com=" + f2(sample.comX()) + "," + f2(sample.comY()) + "," + f2(sample.comZ())
                + " anchor=" + f2(sample.anchorX()) + "," + f2(sample.anchorY()) + "," + f2(sample.anchorZ())
                + " drift=" + f4(sample.anchorDrift()) + " dy=" + f4(sample.verticalError())
                + " joint=" + sample.jointValid()
                + " impulses=" + f4(sample.jointLinearImpulse()) + "/" + f4(sample.jointAngularImpulse())
                + " wheels=[" + wheels + "]";
    }

    private static String f(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String f2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String f4(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private ChassisWorldAnchorProbe() {
    }
}
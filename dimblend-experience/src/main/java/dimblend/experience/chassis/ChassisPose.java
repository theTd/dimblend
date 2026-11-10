package dimblend.experience.chassis;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 车架世界锚的纯姿态数学，不依赖 Sable/Rapier，可直接单测。
 *
 * <p>世界锚必须用真实车体姿态求：Rapier 的 joint 轴是<b>关节帧轴</b>，不是世界轴
 * （{@code GenericJointBuilder::new(locked_axes)} 与 {@code local_frame1/2.rotation}，
 * sable_rapier {@code src/main/rust/rapier/src/joints.rs:660-665}）。因此锁垂向/pitch/roll
 * 的关节帧要取“只保留偏航”的世界帧：X/Z 轴随车体偏航旋转，Y 轴仍是世界竖直。</p>
 *
 * <p>欧拉分解采用 Y-X-Z（先偏航、再俯仰、再横滚），与 JOML/Minecraft 的
 * {@code rotationYXZ} 组合顺序一致；角度单位是度，范围 (-180, 180]。</p>
 *
 * <p><b>固定 yaw 帧不需要每 tick 重锚。</b>锁 ANG_X/ANG_Z、自由 ANG_Y 时，水平 yaw 下
 * 关节帧的 X/Z 仍张成水平面；共同绕 Y 旋转不改变“锁 pitch/roll、放 yaw”的集合。阶段1
 * 的 {@link #bodyFrame} 仍有意保留初始 tilt，不能当正式水平锁。</p>
 */
public final class ChassisPose {
    public record Angles(double yawDegrees, double pitchDegrees, double rollDegrees) {
    }

    public static Angles angles(Quaterniondc rotation) {
        double x = rotation.x();
        double y = rotation.y();
        double z = rotation.z();
        double w = rotation.w();
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(norm > 1.0E-9) || !Double.isFinite(norm)) {
            return new Angles(0.0, 0.0, 0.0);
        }
        x /= norm;
        y /= norm;
        z /= norm;
        w /= norm;

        double sinPitch = 2.0 * (w * x - y * z);
        sinPitch = Math.max(-1.0, Math.min(1.0, sinPitch));
        // R = Ry(yaw) * Rx(pitch) * Rz(roll)，与 JOML rotationYXZ 的组合顺序一致。
        double yaw = Math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (x * x + y * y));
        double pitch = Math.asin(sinPitch);
        double roll = Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (x * x + z * z));
        return new Angles(Math.toDegrees(yaw), Math.toDegrees(pitch), Math.toDegrees(roll));
    }

    /** 只保留车体偏航的世界关节帧。 */
    public static Quaterniond yawFrame(Quaterniondc rotation) {
        return new Quaterniond().rotationY(Math.toRadians(angles(rotation).yawDegrees()));
    }

    /** 把世界关节帧换到车体局部系：bodyFrame = inverse(bodyRotation) * worldFrame。 */
    public static Quaterniond bodyFrame(Quaterniondc bodyRotation, Quaterniondc worldFrame) {
        return new Quaterniond(bodyRotation).normalize().invert()
                .mul(new Quaterniond(worldFrame).normalize()).normalize();
    }

    /**
     * 把 raw plot 点按 Sable 的旋转点公式投到世界：
     * {@code world = bodyPosition + R * (plotPoint - centerOfMass)}。
     *
     * <p>Rust joint 的 body-local anchor 是 {@code pos2 - COM}
     * （sable_rapier {@code joints.rs:109-126}）；{@code Rapier3D.getPose} 返回的
     * {@code position} 不含 rotationPoint，因此这里必须显式减 COM，且只减一次。
     * COM 为 null/非有限时调用方必须先 fail closed，不能传零向量猜。</p>
     */
    public static Vector3d anchor(Quaterniondc bodyRotation, Vector3dc plotPoint, Vector3dc centerOfMass,
                                  Vector3dc bodyPosition, Vector3d dest) {
        return new Quaterniond(bodyRotation).normalize()
                .transform(new Vector3d(plotPoint).sub(centerOfMass), dest).add(bodyPosition);
    }

    private ChassisPose() {
    }
}

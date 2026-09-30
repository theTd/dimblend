package dimblend.experience.compat.create;

/**
 * Create 应力网络未加载份额的扣账口径（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 * 与 {@code KineticNetwork.addSilently}（Create 6.0.10-281 字节码）逐项同口径：
 * 份额 = 存档单位值 × |转速|，扣完小于 0 归 0（NaN 不归零，同字节码 {@code fcmpg/ifge}）。
 * 调用方见 {@link KineticUnloadedShare}。
 */
public final class KineticUnloadedShareMath {

    /** 应力份额：存档 {@code AddedStress} × |存档转速|（addSilently 用读档后未改动的理论转速）。 */
    public static float stressShare(float stressApplied, float speed) {
        return stressApplied * Math.abs(speed);
    }

    /** 容量份额：存档 {@code AddedCapacity} × |自身产出转速|（仅源方块；addSilently 同样取实时产出转速）。 */
    public static float capacityShare(float capacityProvided, float generatedSpeed) {
        return capacityProvided * Math.abs(generatedSpeed);
    }

    /** 从未加载账本扣掉一份，负数归 0。 */
    public static float released(float unloaded, float share) {
        float left = unloaded - share;
        return left < 0.0F ? 0.0F : left;
    }

    /** 未加载成员数减一，负数归 0。 */
    public static int releasedMember(int unloadedMembers) {
        int left = unloadedMembers - 1;
        return left < 0 ? 0 : left;
    }

    private KineticUnloadedShareMath() {
    }
}

package dimblend.experience.compat.coldsweat;

import net.minecraft.util.StringRepresentable;

/**
 * 恒温石（{@code cold_sweat:thermolith}）的三种工作模式。
 *
 * <p>{@link #NORMAL} 必须是第一个枚举常量：{@code StateDefinition.Builder} 的默认状态取每个属性
 * {@code getPossibleValues()} 的首项，所以已放置/新放置的恒温石默认回到原版行为（按温度连续输出
 * 0–15 红石信号），本模组不改变未手动切换过的存档表现。</p>
 *
 * <p>序列化名进方块状态（{@code dimblend_mode=normal|ice|magma}）。</p>
 */
public enum ThermolithMode implements StringRepresentable {

    /** 原版行为：Cold Sweat 每 20 tick 按温度线性输出 0–15。 */
    NORMAL("normal"),
    /** 冰模式：仅当温度高于 40 °C 时输出满 15。 */
    ICE("ice"),
    /** 熔岩模式：仅当温度低于 10 °C 时输出满 15。 */
    MAGMA("magma");

    private final String serializedName;

    ThermolithMode(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return this.serializedName;
    }
}

package dimblend.experience.compat.create;

import java.util.ArrayList;
import java.util.List;

import dimblend.experience.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 分液池 HUD 文案（Jade 与 Create 护目镜共用，两侧口径一致）。
 *
 * <p>输入均为<b>客户端可得</b>数据：水量来自已同步的客户端水箱（Create
 * {@code SmartFluidTankBehaviour} 变化时 sendDataLazily ≤8 tick 节流同步），
 * 催熟剩余 tick 来自 {@code ItemDrainHudSyncMixin} 同步的绝对击发时刻
 * （-1 = 未在计时）；开关/阈值读客户端 ConfigSync 副本（热改不同步已在线
 * 客户端，与 {@code ElectricMotorGoggleMixin} 先例同口径）。</p>
 *
 * <p>显示规则（与功能口径一一对应）：</p>
 * <ul>
 * <li>灌溉行（{@code ITEM_DRAIN_IRRIGATION} 开）：<b>正在下界蒸发</b> → 红色
 * 「蒸发中（N×N）」；水量 &gt; 0 → 灌溉中；否则 → 无水（保湿无最低水量门槛，
 * 见 {@link ItemDrainIrrigation}）。</li>
 * <li>催熟行（{@code ITEM_DRAIN_GROWTH} 开）：水量 &lt; 阈值 → 水量不足（x/y mb）；
 * 剩余 tick ≥ 0 → 下次催熟：X 秒（向上取整，与服务端节拍口径一致）；
 * 否则（范围内无可催熟作物停走、刚复位尚未上弦、同步未达）→ 不出行。</li>
 * </ul>
 */
public final class ItemDrainHud {

    /** NBT/同步键：下次催熟击发的绝对 gameTime（-1 = 未在计时）。 */
    public static final String TAG_NEXT_BOOST_AT = "DimBlendNextBoostAt";

    /** Mixin 实现：提供客户端同步来的下次击发时刻（绝对 gameTime，-1 = 未在计时）。 */
    public interface HasSync {
        long dimblend$nextBoostAt();
    }

    /**
     * HUD 一次渲染所需的全部数据（不可变）。{@code coverageDiameter} 为覆盖面宽
     * （{@code 2 * itemDrainCoverageRadius + 1}，默认 3 → 7 = 7×7）。
     */
    public record Snapshot(int waterMb, long boostTicksLeft, boolean evaporating, int coverageDiameter) {

        /** 兼容旧调用：不带上界蒸发状态。 */
        public static Snapshot of(int waterMb, long boostTicksLeft) {
            return new Snapshot(waterMb, boostTicksLeft, false, 0);
        }
    }

    /**
     * 是否显示「蒸发中」：池内有水、位于下界群系、蒸发开关开、速率 &gt; 0 同时成立。
     * 纯判定（下界判定由调用方算好传入），便于单测（{@code ItemDrainHudTest}）。
     */
    public static boolean shouldShowEvaporating(int waterMb, boolean nether, boolean switchOn, int rateMbPerSecond) {
        return waterMb > 0 && nether && switchOn && rateMbPerSecond > 0;
    }

    /**
     * 生成 HUD 行（0–2 行）。
     *
     * <p>调用约束：仅游戏内渲染路径（护目镜渲染、Jade appendTooltip 等）可调用——
     * 内部直接读 SERVER 配置副本，配置未加载（主菜单/未登录）时 {@code get()} 抛 ISE；
     * 新增调用方若可能处于未加载窗口，须先判 {@code Config.isLoaded()}。</p>
     */
    public static List<Component> hudLines(Snapshot snapshot) {
        List<Component> lines = new ArrayList<>(2);
        if (Config.ITEM_DRAIN_IRRIGATION.get()) {
            if (snapshot.evaporating()) {
                int diameter = Math.max(1, snapshot.coverageDiameter());
                lines.add(Component.translatable("hud.dimblend_experience.item_drain.evaporating",
                        diameter, diameter).withStyle(ChatFormatting.RED));
            } else {
                lines.add(snapshot.waterMb() > 0
                        ? Component.translatable("hud.dimblend_experience.item_drain.irrigating")
                                .withStyle(ChatFormatting.GREEN)
                        : Component.translatable("hud.dimblend_experience.item_drain.no_water")
                                .withStyle(ChatFormatting.GRAY));
            }
        }
        if (Config.ITEM_DRAIN_GROWTH.get()) {
            int minMb = Config.ITEM_DRAIN_GROWTH_MIN_MB.get();
            if (IrrigationMath.belowThreshold(snapshot.waterMb(), minMb)) {
                lines.add(Component.translatable("hud.dimblend_experience.item_drain.insufficient_water",
                                snapshot.waterMb(), minMb)
                        .withStyle(ChatFormatting.YELLOW));
            } else if (snapshot.boostTicksLeft() >= 0) {
                lines.add(Component.translatable("hud.dimblend_experience.item_drain.next_boost",
                                IrrigationMath.ceilSeconds(snapshot.boostTicksLeft()))
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        return lines;
    }

    /** 兼容旧签名（等同 {@link Snapshot#of}）。 */
    public static List<Component> hudLines(int waterMb, long boostTicksLeft) {
        return hudLines(Snapshot.of(waterMb, boostTicksLeft));
    }

    private ItemDrainHud() {
    }
}

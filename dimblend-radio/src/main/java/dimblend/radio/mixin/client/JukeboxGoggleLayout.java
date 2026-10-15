package dimblend.radio.mixin.client;

import dimblend.radio.RadioSignals;
import dimblend.radio.client.TrackTitles;
import dimblend.radio.net.RadioStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 唱片机护目镜弹窗的排版行构造（纯函数，包可见供排版单测）。
 *
 * <p>从 {@code JukeboxGoggleMixin} 拆出：mixin 类禁含非 private static 成员——
 * SpongePowered Mixin 对非私有 static 字段直接抛 InvalidMixinException
 * （实机启动崩溃 2026-10-15，GUTTER/INDENT 为测试接缝降级所致），非私有 static
 * 方法也会被 merge 进目标类造成污染；单测不应用 mixin 抓不到。排版逻辑与测试接缝
 * 收敛于此，mixin 只留注入胶水。</p>
 *
 * <p>基线口径（2026-10-15 修复首行被图标遮挡，javap 出处见 mixin 类 javadoc）：
 * 全行统一 {@link #GUTTER}（4 空格 ≈ catnip {@code LangBuilder.forGoggles} 的
 * {@code getIndents(font, 4)}，默认字体 16px）给左上角 16×16 图标让位；value 行
 * = GUTTER + INDENT（6 空格）。</p>
 */
final class JukeboxGoggleLayout {

    /**
     * 全行基线前导（4 空格）：对齐 catnip {@code LangBuilder.forGoggles} 的
     * {@code getIndents(font, 4)} 口径——默认字体 4px/空格 → 16px，给左上角 16×16
     * 图标让位（2026-10-15 修复）。
     */
    static final String GUTTER = "    ";
    /** value 行缩进（Create label/value 惯例）；最终前导 = GUTTER + INDENT（6 空格）。 */
    static final String INDENT = "  ";
    /** 48 段，每段约 2.08%；用窄字符保持弹窗紧凑。 */
    private static final int BAR_SEGMENTS = 48;
    /** ASCII 细竖线，无额外字体资源要求。 */
    private static final String BAR_BLOCK = "|";

    /** 首行：基线 + 灰 label（台号 + 音量）。 */
    static Component contextLine(RadioStatePayload state) {
        return Component
                .literal(GUTTER + "电台 " + state.station() + "台 · 音量 "
                        + RadioSignals.volumePercent(state.side()) + "%")
                .withStyle(ChatFormatting.GRAY);
    }

    /** 进度行：基线 + 缩进 + 条（AQUA 已播/DARK_GRAY 未播）+ DARK_GRAY 时刻。 */
    static Component progressLine(double elapsedSec, double totalSec) {
        int filled = (int) Math.floor(BAR_SEGMENTS * elapsedSec / totalSec);
        filled = Math.max(0, Math.min(BAR_SEGMENTS, filled));
        var line = Component.literal(GUTTER + INDENT)
                .append(Component.literal(BAR_BLOCK.repeat(filled)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(BAR_BLOCK.repeat(BAR_SEGMENTS - filled))
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component
                        .literal(" " + TrackTitles.formatTime(elapsedSec) + " / "
                                + TrackTitles.formatTime(totalSec))
                        .withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    /** value 行前导 = 基线 + 缩进（6 空格）。 */
    static Component indent(Component text, ChatFormatting color) {
        return Component.literal(GUTTER + INDENT).append(text.copy().withStyle(color));
    }

    private JukeboxGoggleLayout() {
    }
}

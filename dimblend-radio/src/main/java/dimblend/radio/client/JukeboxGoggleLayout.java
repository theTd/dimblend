package dimblend.radio.client;

import dimblend.radio.RadioSignals;
import dimblend.radio.net.RadioStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 唱片机护目镜弹窗的排版行构造（纯函数；public 供 mixin 包的
 * {@code JukeboxGoggleMixin} 与测试包引用）。
 *
 * <p>从 {@code JukeboxGoggleMixin} 拆出，两轮实机事故教训：<b>①mixin 类禁含非
 * private static 成员</b>（SpongePowered Mixin 直接 InvalidMixinException，非私有
 * static 方法还会被 merge 进目标类污染）；<b>②拆出的 helper 类不能放 mixin 专用包</b>
 * （dimblend_radio.mixins.json 声明 package=dimblend.radio.mixin，Mixin 禁包外类直接
 * 引用该包内类——merge 进 JukeboxBlockEntity 的代码一引用就 IllegalClassLoadError，
 * 2026-10-15 实机戴护目镜看唱片机才触发，启动冒烟抓不到）。故本类落在客户端纯逻辑层
 * {@code dimblend.radio.client}（与 RadioController/RadioEndVoices 同层），成员
 * public——跨包引用（mixin.client → client）所需，类 javadoc 即理由。</p>
 *
 * <p>基线口径（2026-10-15 修复首行被图标遮挡，javap 出处见 mixin 类 javadoc）：
 * 全行统一 {@link #GUTTER}（4 空格 ≈ catnip {@code LangBuilder.forGoggles} 的
 * {@code getIndents(font, 4)}，默认字体 16px）给左上角 16×16 图标让位；value 行
 * = GUTTER + INDENT（6 空格）。</p>
 */
public final class JukeboxGoggleLayout {

    /**
     * 全行基线前导（4 空格）：对齐 catnip {@code LangBuilder.forGoggles} 的
     * {@code getIndents(font, 4)} 口径——默认字体 4px/空格 → 16px，给左上角 16×16
     * 图标让位（2026-10-15 修复）。public：供 mixin 包的 JukeboxGoggleMixin 引用。
     */
    public static final String GUTTER = "    ";
    /** value 行缩进（Create label/value 惯例）；最终前导 = GUTTER + INDENT（6 空格）。 */
    public static final String INDENT = "  ";
    /** 48 段，每段约 2.08%；用窄字符保持弹窗紧凑。 */
    private static final int BAR_SEGMENTS = 48;
    /** ASCII 细竖线，无额外字体资源要求。 */
    private static final String BAR_BLOCK = "|";

    /** 首行：基线 + 灰 label（台号 + 音量）。 */
    public static Component contextLine(RadioStatePayload state) {
        return Component
                .literal(GUTTER + "电台 " + state.station() + "台 · 音量 "
                        + RadioSignals.volumePercent(state.side()) + "%")
                .withStyle(ChatFormatting.GRAY);
    }

    /** 进度行：基线 + 缩进 + 条（AQUA 已播/DARK_GRAY 未播）+ DARK_GRAY 时刻。 */
    public static Component progressLine(double elapsedSec, double totalSec) {
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
    public static Component indent(Component text, ChatFormatting color) {
        return Component.literal(GUTTER + INDENT).append(text.copy().withStyle(color));
    }

    private JukeboxGoggleLayout() {
    }
}

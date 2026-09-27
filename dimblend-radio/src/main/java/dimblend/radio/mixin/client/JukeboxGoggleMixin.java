package dimblend.radio.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dimblend.radio.RadioSignals;
import dimblend.radio.client.RadioLibrary;
import dimblend.radio.client.TrackTitles;
import dimblend.radio.net.ClientRadioState;
import dimblend.radio.net.RadioStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * 唱片机护目镜弹窗：戴工程师护目镜看唱片机，显示当前电台曲目。
 *
 * <p>排版跟 Create 原生（见 {@code KineticBlockEntity.addStressImpactStats} 实读）：
 * 灰 label 行开头，value 行两格缩进、主值高亮（AQUA）、后缀暗灰；section 间空行由
 * {@code GoggleOverlayRenderer} 统一加，这里只管自家行。只用原版 Component +
 * ChatFormatting，不碰 CreateLang/TooltipHelper（零 catnip 加载风险）。</p>
 *
 * <ul>
 *   <li>首行（GRAY）：{@code 电台 3台 · 音量 80%}</li>
 *   <li>曲名（WHITE，加粗否）：缩进</li>
 *   <li>作者（GRAY）：缩进，无标签时整行省略</li>
 *   <li>进度：缩进 + 12 段条（AQUA 已播 / DARK_GRAY 未播，字符同 Create 进度条）+
 *   DARK_GRAY {@code 01:23 / 03:45}；本端未知时长时整行省略</li>
 *   <li>缺文件：首行后跟 RED {@code 缺少本地文件，请补曲库} + DARK_GRAY 短 hash
 *   （护目镜即提示位，不再刷聊天栏）</li>
 * </ul>
 *
 * <p>Sable 结构上的唱片机无需特殊处理：Sable 的 clip 重写把对结构的拾取直接改写成
 * plot 坐标的 {@code BlockHitResult}，{@code GoggleOverlayRenderer} 拿它查到的正是
 * plot 位置的这个 BE（与 {@code ClientRadioState} 的 key 同坐标系），{@code getBlockPos()}
 * 即查表 key。距离/声源投影只管播放，不管 tooltip。</p>
 *
 * <p>空盘无电台 → 不加行（不打扰）；有盘（{@code HAS_RECORD}）→ 原版行为，不抢弹窗
 * （客户端 BE 的唱片 Item 不同步，拿不到真名，不显示假名）。</p>
 *
 * <p>client section mixin：只在客户端应用，专用服务器 BE 不动；Create 缺席时由
 * {@code DimBlendRadioMixinPlugin} 按 modid 过滤，不进类加载。</p>
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxGoggleMixin implements IHaveGoggleInformation {

    /** value 行缩进（Create label/value 惯例）。 */
    private static final String INDENT = "  ";
    /** 进度条段数。 */
    private static final int BAR_SEGMENTS = 12;
    /** 进度条字符：Create 进度条同款，护目镜字体可渲染。 */
    private static final String BAR_BLOCK = "█";

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || !level.isClientSide) {
            return false;
        }
        BlockPos pos = self.getBlockPos();
        if (level.getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD)) {
            return false;
        }
        RadioStatePayload state = ClientRadioState.view().get(
                new ClientRadioState.Key(level.dimension().location().toString(), pos.immutable()));
        if (state == null || !state.playing()) {
            return false;
        }
        tooltip.add(contextLine(state));
        String name = RadioLibrary.displayName(state.trackHash());
        if (name == null) {
            // 本端缺文件：护目镜即提示位（RED 警示 + 短 hash），不再刷聊天栏
            tooltip.add(indent(Component.literal("缺少本地文件，请补曲库"), ChatFormatting.RED));
            tooltip.add(indent(Component.literal("hash " + shortHash(state.trackHash())),
                    ChatFormatting.DARK_GRAY));
            return true;
        }
        tooltip.add(indent(Component.literal(name), ChatFormatting.WHITE));
        String artist = RadioLibrary.artistOf(state.trackHash());
        if (artist != null) {
            tooltip.add(indent(Component.literal(artist), ChatFormatting.GRAY));
        }
        double totalSec = RadioLibrary.durationSeconds(state.station(), state.trackHash());
        if (totalSec > 0) {
            double elapsedSec = Math.max(0.0,
                    (Math.max(level.getGameTime(), state.serverNow()) - state.startTick()) / 20.0);
            tooltip.add(progressLine(Math.min(elapsedSec, totalSec), totalSec));
        }
        if (isPlayerSneaking) {
            tooltip.add(indent(Component.literal("hash " + shortHash(state.trackHash())),
                    ChatFormatting.DARK_GRAY));
        }
        return true;
    }

    /** 首行：灰 label（台号 + 音量）。 */
    private static Component contextLine(RadioStatePayload state) {
        return Component
                .literal("电台 " + state.station() + "台 · 音量 "
                        + RadioSignals.volumePercent(state.side()) + "%")
                .withStyle(ChatFormatting.GRAY);
    }

    /** 进度行：缩进 + 条（AQUA 已播/DARK_GRAY 未播）+ DARK_GRAY 时刻。 */
    private static Component progressLine(double elapsedSec, double totalSec) {
        int filled = (int) Math.round(BAR_SEGMENTS * elapsedSec / totalSec);
        filled = Math.max(0, Math.min(BAR_SEGMENTS, filled));
        var line = Component.literal(INDENT)
                .append(Component.literal(BAR_BLOCK.repeat(filled)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(BAR_BLOCK.repeat(BAR_SEGMENTS - filled))
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component
                        .literal(" " + TrackTitles.formatTime(elapsedSec) + " / "
                                + TrackTitles.formatTime(totalSec))
                        .withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    private static Component indent(Component text, ChatFormatting color) {
        return Component.literal(INDENT).append(text.copy().withStyle(color));
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}

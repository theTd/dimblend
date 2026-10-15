package dimblend.radio.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dimblend.radio.client.JukeboxGoggleLayout;
import dimblend.radio.client.RadioLibrary;
import dimblend.radio.client.RadioController;
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
 * <p><b>2026-10-15 修复（用户报告首行被图标遮挡）</b>：Create 6.0.10
 * {@code GoggleOverlayRenderer.renderOverlay} 把悬浮图标（BE 未实现
 * IHaveCustomOverlayIcon 时默认护目镜物品，JukeboxBlockEntity 未实现）画在
 * {@code (posX+10, posY-16)} 的 16×16 区域，文字块经
 * {@code RemovedGuiUtils.drawHoveringText} 从 {@code (posX+12, posY-12)} 开始——
 * 首行左侧约 14px 被图标覆盖（第二行起因垂直位置在图标下方不受影响）。catnip
 * （ponder jar 内）{@code LangBuilder.forGoggles(list, indent)} 给每行加
 * {@code getIndents(font, 4+indent)} 个前导空格——默认字体空格 4px 时即
 * 4+indent 个空格、基线 16px——正是给图标让位；本 mixin 当初为避 catnip 加载风险
 * 用纯原版 Component，漏了这层基线缩进。现全行统一加基线前导（Create 原生就是
 * 全行统一基线、文字块整体右移，不是只给首行加），value 行 = GUTTER + INDENT
 * （6 空格）、首行 = GUTTER（4 空格）；非默认字体下与 catnip 快路径同为近似值。</p>
 *
 * <p><b>2026-10-15 二次/三次修复（两轮实机事故）</b>：排版行构造全部收敛到
 * {@link JukeboxGoggleLayout}（{@code dimblend.radio.client} 层纯函数类，兼作单测
 * 接缝）——①mixin 类禁含非 private static 字段/方法（GUTTER/INDENT 为测试降级后
 * 启动即 InvalidMixinException；非私有 static 方法还会被 merge 进 JukeboxBlockEntity
 * 造成污染，单测不应用 mixin 抓不到）；②拆出的 helper 类禁放 mixin 专用包
 * （dimblend_radio.mixins.json 声明 package=dimblend.radio.mixin，Mixin 禁包外类直接
 * 引用包内类——merge 后的代码一引用就 IllegalClassLoadError，戴护目镜看唱片机才
 * 触发，启动冒烟抓不到）。本类只留注入胶水与 private 成员。</p>
 *
 * <ul>
 *   <li>首行（GRAY）：{@code 电台 3台 · 音量 80%}</li>
 *   <li>曲名（WHITE，加粗否）：基线 + 缩进</li>
 *   <li>作者（GRAY）：基线 + 缩进，无标签时整行省略</li>
 *   <li>进度：基线 + 缩进 + 48 段细条（AQUA 已播 / DARK_GRAY 未播）+
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
        tooltip.add(JukeboxGoggleLayout.contextLine(state));
        String name = RadioLibrary.displayName(state.trackHash());
        if (name == null) {
            // 本端缺文件：护目镜即提示位（RED 警示 + 短 hash），不再刷聊天栏
            tooltip.add(JukeboxGoggleLayout.indent(Component.literal("缺少本地文件，请补曲库"),
                    ChatFormatting.RED));
            tooltip.add(JukeboxGoggleLayout.indent(Component.literal("hash " + shortHash(state.trackHash())),
                    ChatFormatting.DARK_GRAY));
            return true;
        }
        tooltip.add(JukeboxGoggleLayout.indent(Component.literal(name), ChatFormatting.WHITE));
        String artist = RadioLibrary.artistOf(state.trackHash());
        if (artist != null) {
            tooltip.add(JukeboxGoggleLayout.indent(Component.literal(artist), ChatFormatting.GRAY));
        }
        RadioController.Progress progress = RadioController.progress(state);
        if (progress != null && progress.totalSeconds() > 0) {
            tooltip.add(JukeboxGoggleLayout.progressLine(progress.elapsedSeconds(), progress.totalSeconds()));
            if (progress.finished()) {
                tooltip.add(JukeboxGoggleLayout.indent(Component.literal("播放完毕，等待下一曲"),
                        ChatFormatting.GRAY));
            }
        }
        if (isPlayerSneaking) {
            tooltip.add(JukeboxGoggleLayout.indent(Component.literal("hash " + shortHash(state.trackHash())),
                    ChatFormatting.DARK_GRAY));
        }
        return true;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}

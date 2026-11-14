package dimblend.experience.compat.lootjournal;

import java.util.Locale;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认关闭的 Loot Journal 拾取图标诊断（{@code -Ddimblend.lootjournal.icon.diag=true}）。
 *
 * <h2>背景与已核实的真实根因</h2>
 * <p>2026-11-10 与 2026-11-14 玩家报告「捡起物品时文字还在，物品图标不见了」。经玩家端实际
 * {@code config/obscuria/loot_journal-client.toml} 与 {@code loot_journal-neoforge-1.21.1-6.2.2.jar}
 * 字节码取证：</p>
 * <ul>
 *   <li>玩家配置第 44 行为 {@code elementOrder = "COUNT 4px NAME 4px ICON 4px TOTA"}（末尾少写一个 {@code L}），
 *       {@code LayoutParser#createElement("TOTA")} 抛 {@code IllegalArgumentException}，被
 *       {@code ConfigCache#refresh} 捕获后回退到 {@code PickupLayout.DEFAULT}，而上游
 *       {@code PickupLayout.DEFAULT} 静态常量实为 {@code new PickupLayout(List.of(NameToken.SHARED))}
 *       ——仅剩 {@code NAME}，连同 {@code ICON} 一并丢弃。该主根因现由
 *       {@link LootJournalLayoutSanitizer} 自动修复。</li>
 *   <li>此前 commit {@code 9f36d86} 猜测的「ImmediatelyFast {@code hud_batching} 致图标丢失」已被三重证据否定：
 *       玩家端 spark {@code SOURCES} 根本未装 ImmediatelyFast；本机 {@code b689741^} 在兼容层未应用时
 *       （{@code getClassNode(name, false)} 抛异常跳过）实拍 {@code item3_loot_journal_icons_*.png}
 *       图标完全正常；旧兼容层每帧无谓调用 {@code flush} 与 GL 状态覆写，因此已删除，仅保留本类的只读采样钩子。</li>
 * </ul>
 *
 * <h2>一行日志回答什么</h2>
 * <p>采样点在 {@code dev.obscuria.lootjournal.client.renderer.PickupComponent#render} 入口，
 * 即整个拾取 HUD 组件（面板 / 文字 / 图标同处一个 {@code GuiGraphics} 与 pose）。各字段：</p>
 * <ul>
 *   <li>{@code source}：此刻 {@code GuiGraphics#bufferSource} 的真实实现类。</li>
 *   <li>{@code depthTest}：入口处的 {@code GL_DEPTH_TEST}。</li>
 *   <li>{@code color}：入口处的 {@code RenderSystem} shader color。</li>
 *   <li>{@code poseZ}：入口 pose 的累计 z（组件内图标最终落在 {@code poseZ + 230}）。</li>
 *   <li>{@code hideGui}：{@code options.hideGui} 为真时组件内部整段不画。</li>
 * </ul>
 *
 * <p>采样全局 2 秒最多一行，默认关闭时只做一次 {@code boolean} 判断，不读 GL、不分配。
 * 本类<b>不修改</b>任何 GL / 渲染状态，也不碰 {@code GuiGraphics} 的 buffer source。</p>
 */
public final class LootJournalIconDiagnostics {

    /** 采样总开关，默认关闭。 */
    static final boolean ENABLED = Boolean.getBoolean("dimblend.lootjournal.icon.diag");
    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend_experience/loot-journal-icon-diag");
    /** 采样最短间隔（毫秒）：全局共享，2 秒最多一行。 */
    private static final long SAMPLE_INTERVAL_MS = 2_000L;

    private static final IconSamplingWindow WINDOW = new IconSamplingWindow(SAMPLE_INTERVAL_MS);

    /**
     * 记录一次拾取 HUD 组件的绘制入口状态。
     *
     * @param graphics 该组件拿到的 {@link GuiGraphics}（为 {@code null} 时直接返回）
     */
    public static void sample(GuiGraphics graphics) {
        if (!ENABLED || graphics == null) {
            return;
        }
        if (!WINDOW.tryAcquire(System.currentTimeMillis())) {
            return;
        }
        LOGGER.info("[loot-journal-icon] {}", describe(
                bufferSourceName(graphics),
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                RenderSystem.getShaderColor(),
                graphics.pose().last().pose().m32(),
                hideGui()));
    }

    static String bufferSourceName(GuiGraphics graphics) {
        return graphics.bufferSource() == null ? "null" : graphics.bufferSource().getClass().getName();
    }

    static boolean hideGui() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.options != null && minecraft.options.hideGui;
    }

    /**
     * 把采样值拼成一行日志正文；纯函数，便于单测钉死字段与缺值口径。
     *
     * @param color 长度为 4 的 RGBA；为 {@code null} 或长度不足时记 {@code n/a}，不猜 0
     */
    static String describe(String bufferSource, boolean depthTest, float[] color, float poseZ, boolean hideGui) {
        String colorText = color == null || color.length < 4
                ? "color=n/a"
                : String.format(Locale.ROOT, "color=(%.3f,%.3f,%.3f,%.3f)", color[0], color[1], color[2], color[3]);
        return "source=" + bufferSource
                + " depthTest=" + depthTest
                + " " + colorText
                + String.format(Locale.ROOT, " poseZ=%.1f", poseZ)
                + " hideGui=" + hideGui;
    }

    private LootJournalIconDiagnostics() {
    }
}

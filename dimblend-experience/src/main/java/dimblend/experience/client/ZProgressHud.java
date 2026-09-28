package dimblend.experience.client;

import dimblend.experience.Config;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * z256 进度条：画在盔甲 HUD 列、与原版盔甲行同宽 81px，
 * 包裹 {@code VanillaGuiLayers.ARMOR_LEVEL} 在盔甲行渲染完立刻绘制，读到的 {@code Gui.leftHeight} 就是盔甲行刚用过的值）。
 * 生存/冒险/创造均显示，旁观隐藏：flatten 后盔甲包裹在创造下也会触发
 * （内层原版渲染被跳过、但包裹无条件执行），故 {@code render()} 自拦到生存/冒险，
 * 创造改由 {@link #renderCreativeFallback} 经 HOTBAR 包裹层回退绘制，落点与生存一致。
 *
 * <p>显示规则见 docs/z256-bar-requirement.md：常驻显示当前段内进度
 * {@code (|z| % 256) / 256}，数字只显示 {@code |z|} 取整（{@code |z|≤16} 时隐藏数字、
 * 仅保留进度条）。回程瞬时归零（实测修订，原 2026-09-22 锁存口径作废）：回程且段内位置
 * 严格大于 128 的当刻显示 0%，一旦转头前进立刻按公式恢复，无锁存记忆
 * （回程判定带 0.5 格防抖，站立抖动不归零）。颜色按最终进度：
 * [0,60%) 绿、[60%,80%) 黄、[80%,100%] 红。
 *
 * <p>与扣血逻辑（DepthCurse）的边界：本条只读 {@code |z|} 和客户端自采的方向，
 * 不读生命上限/层数；扣血逻辑也不读本条进度。256/128 常数各自独立定义。
 */
public final class ZProgressHud {
    /** 数字隐藏半径：|z|≤16 时只画进度条、不画数字（新条目）。 */
    static final int NUMBER_HIDE_RADIUS = 16;
    /** 回程判定防抖：单次采样回退超过半格才算回程，站立抖动不归零。 */
    static final double RETURN_EPSILON = 0.5;
    /** 方向采样间隔（tick），与服务端 DepthCurse 的 TICK_INTERVAL 对齐。 */
    private static final int SAMPLE_INTERVAL = 10;

    private static final int BAR_WIDTH = 81; // 盔甲行：10 图标 × 8px 步进，末图标 9px 宽
    private static final int BAR_HEIGHT = 5;
    private static final int GAP_ABOVE_ARMOR = 1;
    /** 创造回退槽位：Gui 初值 leftHeight=39 + 生存基准一行血量 10，槽位顶恒为 guiHeight - 49。 */
    private static final int CREATIVE_SLOT_TOP_OFFSET = 49;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int GREEN = 0xFF35C835;
    private static final int YELLOW = 0xFFE0C020;
    private static final int RED = 0xFFE04040;

    /** 上次采样点的 |z|；null 表示尚未建基线（首次/切维度回来后按去程处理）。 */
    private static Double lastAbsZ;
    private static int lastSampleTick;
    private static boolean returning;

    private ZProgressHud() {
    }

    /** 客户端 tick 采样方向：每 10 tick 比较一次 |z|，变小超 0.5 格即回程（含重生 tick 归零重建基线）。 */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !RotatingDimension.is(mc.level)) {
            lastAbsZ = null;
            returning = false;
            return;
        }
        double absZ = Math.abs(mc.player.getZ());
        int tick = mc.player.tickCount;
        if (lastAbsZ == null || tick < lastSampleTick) {
            // 基线重建（首次采样/切维度回来/重生 tick 归零）：方向复位
            returning = false;
            lastAbsZ = absZ;
            lastSampleTick = tick;
            return;
        }
        if (tick - lastSampleTick >= SAMPLE_INTERVAL) {
            returning = lastAbsZ - absZ > RETURN_EPSILON;
            lastAbsZ = absZ;
            lastSampleTick = tick;
        }
    }

    /** 按左闭右开边界取色：[0,60%) 绿、[60%,80%) 黄、[80%,100%] 红。 */
    static int colorFor(float progress) {
        if (progress >= 0.8F) {
            return RED;
        }
        if (progress >= 0.6F) {
            return YELLOW;
        }
        return GREEN;
    }

    /** 包裹 ARMOR_LEVEL 后调用：在盔甲行位置画常驻进度条。不在旋转维度/关开关/旁观时隐藏，仅生存/冒险（创造走 {@link #renderCreativeFallback}，否则两层各画一条）。 */
    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        // wrapLayer 包裹无条件执行：创造下盔甲层内部空渲染，但本入口仍会被调到；
        // 不在这里拦，HOTBAR 回退会再画一条（创造双条的根因）。
        if (mc.gameMode == null || !mc.gameMode.canHurtPlayer()) {
            return;
        }
        Player player = visiblePlayer(mc);
        if (player == null) {
            return;
        }
        int armorValue = player.getArmorValue();
        // 有盔甲时盔甲层已给 leftHeight +10，图标顶在 guiHeight - leftHeight + 10；
        // 无盔甲时什么都没画，行槽位顶就是 guiHeight - leftHeight。
        int slotTop = graphics.guiHeight() - mc.gui.leftHeight + (armorValue > 0 ? 10 : 0);
        int barTop = armorValue > 0 ? slotTop - GAP_ABOVE_ARMOR - BAR_HEIGHT : slotTop;
        draw(graphics, mc, player, barTop);
    }

    /**
     * 创造模式回退入口，由 HOTBAR 包裹层调用。盔甲包裹在创造下也会触发，
     * 故 {@code render()} 自拦到生存/冒险、这里只画创造（旁观由 {@code visiblePlayer} 排除）：
     * 按生存基准（初值 39 + 一行血量 10）固定落到同一盔甲槽位。生存/冒险已由盔甲层画过，这里跳过防重画。
     */
    public static void renderCreativeFallback(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null && mc.gameMode.canHurtPlayer()) {
            return;
        }
        Player player = visiblePlayer(mc);
        if (player == null) {
            return;
        }
        // Gui 每帧初值 leftHeight=39，生存基准再加一行血量 10，槽位顶恒为 guiHeight - 49。
        int slotTop = graphics.guiHeight() - CREATIVE_SLOT_TOP_OFFSET;
        int barTop = player.getArmorValue() > 0 ? slotTop - GAP_ABOVE_ARMOR - BAR_HEIGHT : slotTop;
        draw(graphics, mc, player, barTop);
    }

    /** 通用门控：hideGui/维度/开关/旁观（创造放行），通过才返回相机玩家。 */
    private static Player visiblePlayer(Minecraft mc) {
        if (mc.options.hideGui) {
            return null;
        }
        if (mc.level == null || mc.gameMode == null) {
            return null;
        }
        // canHurtPlayer 仅生存/冒险才 true，创造需放行，只排除旁观。
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return null;
        }
        if (!RotatingDimension.is(mc.level)) {
            return null;
        }
        if (!Config.CURSE_BOSSBAR.get()) {
            return null;
        }
        if (!(mc.getCameraEntity() instanceof Player player)) {
            return null;
        }
        return player;
    }

    private static void draw(GuiGraphics graphics, Minecraft mc, Player player, int barTop) {
        double absZ = Math.abs(player.getZ());
        float progress = ZProgressMath.progressFor(absZ, returning);
        int left = graphics.guiWidth() / 2 - 91;
        graphics.fill(left, barTop, left + BAR_WIDTH, barTop + BAR_HEIGHT, BACKGROUND_COLOR);
        int fillWidth = Math.round(progress * (BAR_WIDTH - 2));
        if (fillWidth > 0) {
            graphics.fill(left + 1, barTop + 1, left + 1 + fillWidth, barTop + BAR_HEIGHT - 1, colorFor(progress));
        }
        if (absZ > NUMBER_HIDE_RADIUS) {
            int textY = barTop + (BAR_HEIGHT - mc.font.lineHeight) / 2;
            graphics.drawCenteredString(mc.font, Integer.toString((int) absZ), left + BAR_WIDTH / 2, textY, 0xFFFFFFFF);
        }
    }
}

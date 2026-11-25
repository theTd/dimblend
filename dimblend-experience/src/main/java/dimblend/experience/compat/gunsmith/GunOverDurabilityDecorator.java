package dimblend.experience.compat.gunsmith;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * 枪械第二行蓝色过度维修耐久条（Audit Default #11）：
 *
 * <ul>
 *   <li>当 {@code overDurability > 0 && maxDamage > 0} 时：
 *     <ul>
 *       <li>若原耐久已满（{@code stack.getDamageValue() == 0}，原版此时会隐藏下排耐久条），
 *           在下排 {@code (x + 2, y + 13)} 补画 {@code 13×2} 纯黑底框（{@code 0xFF000000}）
 *           与 {@code 13×1} 满格绿色条（{@code 0xFF00FF00}）；</li>
 *       <li>在紧上方 {@code (x + 2, y + 11)} 绘制 {@code 13×2} 纯黑底框（{@code 0xFF000000}）
 *           与 {@code blueWidth×1} 亮蓝色条（{@code 0xFF3AA8FF}），宽度为
 *           {@code clamp(round(13.0f * overDurability / maxDamage), 1, 13)}。</li>
 *     </ul>
 *   </li>
 *   <li>与原版 {@code GuiGraphics#renderItemDecorations} 一致，在 {@code z = 200} 使用
 *       {@link RenderType#guiOverlay()} 绘制，确保永远压在 3D 枪械图标之上。</li>
 *   <li>绝不向 Tooltip 或 HUD 输出任何枪械经验等级或数值加成文本。</li>
 * </ul>
 */
public final class GunOverDurabilityDecorator implements IItemDecorator {

    public static final GunOverDurabilityDecorator INSTANCE = new GunOverDurabilityDecorator();

    private static final int BAR_WIDTH = 13;
    private static final int BAR_LEFT_OFFSET = 2;
    private static final int TOP_BAR_Y_OFFSET = 11;
    private static final int BOTTOM_BAR_Y_OFFSET = 13;

    private static final int BG_COLOR = 0xFF000000;
    private static final int FULL_GREEN_COLOR = 0xFF00FF00;
    private static final int BLUE_OVER_COLOR = 0xFF3AA8FF;

    @Override
    public boolean render(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        int overDurability = GunsmithData.getOverDurability(stack);
        int maxDamage = stack.getMaxDamage();
        if (overDurability <= 0 || maxDamage <= 0) {
            return false;
        }

        int left = x + BAR_LEFT_OFFSET;
        int topY = y + TOP_BAR_Y_OFFSET;
        int bottomY = y + BOTTOM_BAR_Y_OFFSET;
        int blueWidth = Mth.clamp(Math.round(BAR_WIDTH * (float) overDurability / (float) maxDamage), 1, BAR_WIDTH);

        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, 200.0f);
        if (stack.getDamageValue() == 0) {
            graphics.fill(RenderType.guiOverlay(), left, bottomY, left + BAR_WIDTH, bottomY + 2, BG_COLOR);
            graphics.fill(RenderType.guiOverlay(), left, bottomY, left + BAR_WIDTH, bottomY + 1, FULL_GREEN_COLOR);
        }
        graphics.fill(RenderType.guiOverlay(), left, topY, left + BAR_WIDTH, topY + 2, BG_COLOR);
        graphics.fill(RenderType.guiOverlay(), left, topY, left + blueWidth, topY + 1, BLUE_OVER_COLOR);
        graphics.pose().popPose();
        return true;
    }

    private GunOverDurabilityDecorator() {
    }
}

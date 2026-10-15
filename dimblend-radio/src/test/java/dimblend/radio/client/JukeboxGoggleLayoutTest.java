package dimblend.radio.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import dimblend.radio.net.RadioStatePayload;

/**
 * 护目镜弹窗排版（2026-10-15 修复首行被左上角 16×16 图标遮挡）：全行统一
 * {@link JukeboxGoggleLayout#GUTTER} 基线前导（对齐 catnip forGoggles 的
 * getIndents(font, 4) 口径，默认字体 16px），value 行再叠 {@link JukeboxGoggleLayout#INDENT}。
 * 排版逻辑收敛在 JukeboxGoggleLayout（dimblend.radio.client 层）——mixin 类禁含非
 * private static 成员、拆出的 helper 类禁放 mixin 专用包（两轮实机事故
 * 2026-10-15），本测试只碰纯函数布局类。
 */
class JukeboxGoggleLayoutTest {

    private static RadioStatePayload state() {
        return new RadioStatePayload(ResourceLocation.withDefaultNamespace("overworld"),
                BlockPos.ZERO, 3, 15, "hash", 0L, 0, true, 0L);
    }

    @Test
    void gutterIsFourSpacesAndIndentTwoMatchingTheCatnipBaseline() {
        assertEquals("    ", JukeboxGoggleLayout.GUTTER);
        assertEquals("  ", JukeboxGoggleLayout.INDENT);
    }

    @Test
    void contextLineCarriesOnlyTheBaselineLead() {
        String line = JukeboxGoggleLayout.contextLine(state()).getString();
        assertTrue(line.startsWith(JukeboxGoggleLayout.GUTTER), "first line keeps the icon gutter: " + line);
        assertTrue(!line.startsWith(JukeboxGoggleLayout.GUTTER + JukeboxGoggleLayout.INDENT),
                "first line is the label row, not a value row: " + line);
    }

    @Test
    void valueRowsCarryBaselinePlusIndent() {
        String indented = JukeboxGoggleLayout.indent(Component.literal("x"), ChatFormatting.WHITE).getString();
        assertTrue(indented.startsWith(JukeboxGoggleLayout.GUTTER + JukeboxGoggleLayout.INDENT),
                "value row lead = gutter + indent (6 spaces): " + indented);
        String progress = JukeboxGoggleLayout.progressLine(30.0, 60.0).getString();
        assertTrue(progress.startsWith(JukeboxGoggleLayout.GUTTER + JukeboxGoggleLayout.INDENT),
                "progress row lead = gutter + indent: " + progress);
    }
}

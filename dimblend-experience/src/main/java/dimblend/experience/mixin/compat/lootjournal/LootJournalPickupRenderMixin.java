package dimblend.experience.mixin.compat.lootjournal;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.lootjournal.LootJournalIconDiagnostics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Loot Journal 拾取 HUD 组件的<b>只读</b>诊断采样钩子。
 *
 * <p>目标类 {@code dev.obscuria.lootjournal.client.renderer.PickupComponent} 不在本模块的编译
 * classpath 上，因此只用 {@code targets} 字符串 + {@link Pseudo}，方法签名按 {@code javap -s}
 * 实读的描述符写死：{@code public static void render(GuiGraphics, DeltaTracker)} -&gt;
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V}。
 * {@code remap = false}：目标不是原版/NeoForge 类，描述符里的原版名字就是运行期名字。</p>
 *
 * <p>此前在这里换 {@code GuiGraphics#bufferSource} 并改写 shader color / 深度测试的兼容层已随
 * ImmediatelyFast 归因证伪删除（玩家「文字还在、图标不见」的真实根因为 {@code elementOrder} 拼写错字触发
 * {@code PickupLayout.DEFAULT = [NAME]}，已由 {@link LootJournalLayoutSanitizerMixin} 修复；详见
 * {@link LootJournalIconDiagnostics}）。</p>
 */
@Pseudo
@Mixin(targets = "dev.obscuria.lootjournal.client.renderer.PickupComponent", remap = false)
public abstract class LootJournalPickupRenderMixin {

    @WrapMethod(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            require = 1, allow = 1)
    private static void dimblend$sampleIconState(GuiGraphics graphics, DeltaTracker deltaTracker,
            Operation<Void> original) {
        LootJournalIconDiagnostics.sample(graphics);
        original.call(graphics, deltaTracker);
    }
}

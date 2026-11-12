package dimblend.experience.mixin.compat.lootjournal;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.lootjournal.LootJournalHudCompat;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Loot Journal 的拾取提示整体包一层：让它的物品图标走原版立即绘制路径。
 *
 * <p>目标类 {@code dev.obscuria.lootjournal.client.renderer.PickupComponent} 不在本模块的编译
 * classpath 上，因此只用 {@code targets} 字符串 + {@link Pseudo}，方法签名按 {@code javap -s} 实读的
 * 描述符写死：{@code public static void render(GuiGraphics, DeltaTracker)} -&gt;
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V}。
 * {@code remap = false}：目标不是原版/NeoForge 类，描述符里的原版名字就是运行期名字。</p>
 *
 * <p>用 {@code @WrapMethod} 而不是 HEAD/RETURN 两个注入点：换 {@code bufferSource} 与恢复必须
 * 在同一个 try/finally 里，绘制体抛异常时也要把 source 换回去（见
 * {@link LootJournalHudCompat#renderUnbatched}）。目标是 {@code static} 方法，因此处理器必须是
 * {@code private static}（Mixin 对静态目标的要求）。</p>
 */
@Pseudo
@Mixin(targets = "dev.obscuria.lootjournal.client.renderer.PickupComponent", remap = false)
public abstract class LootJournalPickupRenderMixin {

    @WrapMethod(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            require = 1, allow = 1)
    private static void dimblend$renderUnbatched(GuiGraphics graphics, DeltaTracker deltaTracker,
            Operation<Void> original) {
        final LootJournalHudCompat.UnbatchedScope scope = LootJournalHudCompat.beginUnbatched(graphics);
        try {
            original.call(graphics, deltaTracker);
        } finally {
            LootJournalHudCompat.endUnbatched(graphics, scope);
        }
    }
}

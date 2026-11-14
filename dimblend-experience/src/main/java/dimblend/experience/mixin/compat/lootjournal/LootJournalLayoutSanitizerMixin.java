package dimblend.experience.mixin.compat.lootjournal;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.lootjournal.LootJournalLayoutSanitizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 拦截 {@code dev.obscuria.lootjournal.client.renderer.layout.LayoutParser#normalize(String)}，
 * 防止 {@code elementOrder} 配置拼写错字（如 {@code TOTA}）触发异常并回退到只含 {@code NAME}、
 * 丢失物品图标的 {@code PickupLayout.DEFAULT}。
 *
 * <p>目标方法描述符经 {@code loot_journal-neoforge-1.21.1-6.2.2.jar} {@code javap -p -s} 核实为
 * {@code private static java.lang.String normalize(java.lang.String)} -&gt;
 * {@code (Ljava/lang/String;)Ljava/lang/String;}，方法签名完全由 JDK {@link String} 组成，
 * 不引用任何 Loot Journal 内部类型。</p>
 */
@Pseudo
@Mixin(targets = "dev.obscuria.lootjournal.client.renderer.layout.LayoutParser", remap = false)
public abstract class LootJournalLayoutSanitizerMixin {

    @WrapMethod(method = "normalize(Ljava/lang/String;)Ljava/lang/String;", require = 1, allow = 1)
    private static String dimblend$sanitizeNormalized(String raw, Operation<String> original) {
        return LootJournalLayoutSanitizer.sanitizeNormalized(
                raw, raw == null ? null : original.call(raw));
    }
}

package dimblend.craft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

/**
 * 暴露 {@code I18n.setLanguage(Language)}（包私有静态方法，neoforge-21.1.249
 * {@code net/minecraft/client/resources/language/I18n.java}）给覆盖安装点。
 *
 * <p>原版 {@code LanguageManager.onResourceManagerReload} 先调 {@code I18n.setLanguage}、
 * 再调 {@code Language.inject}，两处持有同一个语言实例；TAIL 安装覆盖层时两边都要替换，
 * 否则 {@code I18n.get} 走的仍是旧表。</p>
 */
@Mixin(I18n.class)
public interface I18nAccessor {

    @Invoker("setLanguage")
    static void dimblend$setLanguage(Language language) {
        throw new AssertionError();
    }
}

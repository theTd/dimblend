package dimblend.craft.mixin.client;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.resources.ResourceManager;

import dimblend.craft.DimblendCraft;
import dimblend.craft.client.LanguageOverrides;
import dimblend.craft.client.OverlayLanguage;

/**
 * 客户端语言重载完成后安装外 namespace 显示名覆盖层（2026-10-31 R12）。
 *
 * <p><b>为什么不用随包 {@code assets/<对方命名空间>/lang/<code>.json} 覆盖</b>
 * （结论：资源包顺序由 jar 文件名决定，不可靠，故用本 mixin 的确定性做法；只选一种）：</p>
 *
 * <ul>
 *   <li>原版合并规则：{@code ClientLanguage.loadFrom}（neoforge-21.1.249-sources.jar
 *       {@code net/minecraft/client/resources/language/ClientLanguage.java:39-58}）按命名空间
 *       逐个 {@code resourceManager.getResourceStack(assets/<ns>/lang/<code>.json)}（同文件:50），
 *       再 {@code appendFrom}（同文件:65-68）用 {@code destinationMap::put} 合并 ——
 *       <b>栈里靠后的资源逐键覆盖前面的</b>。</li>
 *   <li>资源栈顺序：{@code FallbackResourceManager.getResourceStack}
 *       （同 jar {@code net/minecraft/server/packs/resources/FallbackResourceManager.java:98-139}）
 *       从最后 push 的资源包往前收集，末尾 {@code Lists.reverse}（同文件:138），所以返回顺序就是
 *       {@code MultiPackResourceManager} 的 pack 列表顺序，<b>列表里越靠后的 mod 越优先</b>。</li>
 *   <li>mod 资源包顺序：{@code ResourcePackLoader.findResourcePacks}
 *       （{@code net/neoforged/neoforge/resource/ResourcePackLoader.java:77-86}）按
 *       {@code ModList.get().getModFiles()} 顺序建子包，作为必选包 {@code mod_resources} 的
 *       children（同文件:141、195-204）；而 {@code ModList.getModFiles()}（fancymodloader
 *       loader-4.0.44 {@code net/neoforged/fml/ModList.java:77}）返回的是<b>发现顺序</b>列表
 *       （依赖排序后的 {@code sortedList} 才是加载顺序，与这里无关），发现顺序来自
 *       {@code ModsFolderLocator.findCandidates}（同 jar
 *       {@code net/neoforged/fml/loading/moddiscovery/locators/ModsFolderLocator.java:58}），
 *       只按 <b>jar 文件名小写排序</b>。整合包又会给 jar 加 {@code [中文名] } 前缀排顺序，
 *       任何重命名都会在无报错的情况下翻转覆盖优先级。</li>
 *   <li>此外同一命名空间内不同 mod 的 lang 文件也受该顺序影响，且 {@code loadFrom} 遍历
 *       {@code getNamespaces()}（同文件:47）的集合顺序本身不是约定的一部分，跨命名空间的
 *       覆盖更不可能保证。</li>
 * </ul>
 *
 * <p>因此这里选唯一一种机制：在 {@code LanguageManager.onResourceManagerReload}
 * （{@code LanguageManager.java:53-69}，其内部依次 {@code I18n.setLanguage}
 * 与 {@code Language.inject}，同文件:66-68）的 TAIL 安装覆盖层。每次重载都会重新构造
 * 新的 {@code ClientLanguage}，所以这里包一层不会累积；覆盖表来自本 mod 自己的 classpath
 * 资源（{@link LanguageOverrides}），与整合包资源包顺序无关。</p>
 */
@Mixin(LanguageManager.class)
public class LanguageOverrideMixin {

    @Inject(method = "onResourceManagerReload", at = @At("TAIL"))
    private void dimblend$installNameOverrides(ResourceManager resourceManager, CallbackInfo ci) {
        String selected = ((LanguageManager) (Object) this).getSelected();
        Map<String, String> overrides = LanguageOverrides.forLanguage(selected);
        if (overrides.isEmpty()) {
            DimblendCraft.LOGGER.warn("[语言] 显示名覆盖表为空（当前语言 {}），本次不安装覆盖，"
                    + "请检查 assets/dimblend_craft/lang_overrides/", selected);
            return;
        }
        Language base = Language.getInstance();
        OverlayLanguage overlay = new OverlayLanguage(base, overrides);
        Language.inject(overlay);
        I18nAccessor.dimblend$setLanguage(overlay);
        DimblendCraft.LOGGER.info("[语言] 已安装 {} 条显示名覆盖（当前语言 {}）", overrides.size(), selected);
    }
}

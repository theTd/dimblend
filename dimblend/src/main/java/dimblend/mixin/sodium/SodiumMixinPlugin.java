package dimblend.mixin.sodium;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Optional gate for the S2 Sodium mixins, same pattern as {@code SableMixinPlugin}.
 *
 * <p>{@code required:false} in {@code dimblend.sodium.mixins.json} plus this plugin means a pack
 * without Sodium still boots: no mixin is applied, {@code ShaderParserInjectionMixin} never runs,
 * and {@code SodiumRiftBridge.available()} reports {@code false} so RIFT mode degrades to the
 * Phase-1 membrane. Detection uses {@link ClassLoader#getResource} instead of
 * {@code Class.forName} so no Sodium class is defined during mixin discovery.</p>
 */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    private static final String PROBE_CLASS_RESOURCE =
            "net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class";
    private static final boolean SODIUM_PRESENT = detectSodium();

    private static boolean detectSodium() {
        if (FMLLoader.getLoadingModList().getModFileById("sodium") == null) {
            return false;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader.getResource(PROBE_CLASS_RESOURCE) != null) {
            return true;
        }
        ClassLoader pluginLoader = SodiumMixinPlugin.class.getClassLoader();
        return pluginLoader != null
                && pluginLoader != contextLoader
                && pluginLoader.getResource(PROBE_CLASS_RESOURCE) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return SODIUM_PRESENT;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

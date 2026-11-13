package dimblend.mixin.sable;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Optional gate for Sable accessors so {@code dimblend} still boots cleanly without Sable.
 * Uses {@link ClassLoader#getResource} instead of {@code Class.forName} or ASM bytecode loading
 * to avoid premature class definition or ModLauncher transformer exceptions.
 */
public final class SableMixinPlugin implements IMixinConfigPlugin {
    private static final String PROBE_CLASS_RESOURCE = "dev/ryanhcode/sable/sublevel/ServerSubLevel.class";
    private static final boolean SABLE_PRESENT = detectSable();

    private static boolean detectSable() {
        if (FMLLoader.getLoadingModList().getModFileById("sable") == null) {
            return false;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null && contextLoader.getResource(PROBE_CLASS_RESOURCE) != null) {
            return true;
        }
        ClassLoader pluginLoader = SableMixinPlugin.class.getClassLoader();
        return pluginLoader != null
                && pluginLoader != contextLoader
                && pluginLoader.getResource(PROBE_CLASS_RESOURCE) != null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return SABLE_PRESENT;
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

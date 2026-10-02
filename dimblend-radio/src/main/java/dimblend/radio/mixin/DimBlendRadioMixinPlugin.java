package dimblend.radio.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/**
 * 按目标模组存在性过滤 mixin：Create 缺席时护目镜 mixin 不应用，
 * 保证“单独安装本模组不崩”（与 dimblend-experience 的 DimBlendMixinPlugin 同约定）。
 * 未列出的 mixin（原版目标）无条件应用。
 */
public class DimBlendRadioMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".client.JukeboxGoggleMixin")) {
            return isLoaded("create");
        }
        if (mixinClassName.startsWith("dimblend.radio.mixin.client.sodium.")) {
            return isSodiumCompatible();
        }
        return true;
    }

    private static boolean isLoaded(String modId) {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    /**
     * The geometry tee hooks Sodium-internal classes whose layout is only verified for the 0.8.x
     * 1.21.1 backport line; anything else keeps the voxel acoustic path instead of crashing.
     */
    private static boolean isSodiumCompatible() {
        var file = net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById("sodium");
        return file != null && file.versionString().startsWith("0.8.");
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
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }
}

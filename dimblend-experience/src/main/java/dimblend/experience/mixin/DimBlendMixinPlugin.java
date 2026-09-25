package dimblend.experience.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/**
 * 按目标模组存在性过滤 mixin：第三方目标类缺失时该 mixin 不应用，
 * 保证"单独安装本模组不崩"（横切约定）。映射：mixin 类名 → 必须在场的模组 id。
 * 未列出的 mixin（原版目标）无条件应用。
 */
public class DimBlendMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".compat.cdg.DieselEngineRampMixin")
                || mixinClassName.endsWith(".compat.cdg.EngineFuelGateMixin")
                || mixinClassName.endsWith(".compat.cdg.HugeDieselEngineMixin")) {
            return isLoaded("createdieselgenerators");
        }
        if (mixinClassName.endsWith(".compat.cca.ElectricMotorMixin")
                || mixinClassName.endsWith(".compat.cca.ElectricMotorSoundClientMixin")
                || mixinClassName.endsWith(".compat.cca.AlternatorIdleDrainMixin")
                || mixinClassName.endsWith(".compat.cca.ElectricMotorGoggleMixin")
                // 目标是 Create 的 GeneratingKineticBlockEntity，但行为仅对 CCA 电动马达
                // 生效（handler instanceof 门控），故按 createaddition 在场性过滤
                || mixinClassName.endsWith(".compat.create.ElectricMotorGeneratorStatsMixin")) {
            return isLoaded("createaddition");
        }
        // simurail 硬性依赖 sable（mods.toml required），在场性蕴含 sable 在场
        if (mixinClassName.endsWith(".compat.simurail.PhysicsBogeyBrakeSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyLateralForceMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyTrackSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyWideGaugeParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerRedstoneMixin")) {
            return isLoaded("simurail");
        }
        if (mixinClassName.endsWith(".compat.dimblend.WarpGateBlockMixin")) {
            return isLoaded("dimblend");
        }
        if (mixinClassName.endsWith(".compat.create.ItemDrainIrrigationMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainGrowthBoostMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainPipeRefillMixin")) {
            return isLoaded("create");
        }
        if (mixinClassName.endsWith(".compat.fluid.GutterOutletPrecipitationMixin")) {
            return isLoaded("fluid");
        }
        return true;
    }

    private static boolean isLoaded(String modId) {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(modId) != null;
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
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
    }
}
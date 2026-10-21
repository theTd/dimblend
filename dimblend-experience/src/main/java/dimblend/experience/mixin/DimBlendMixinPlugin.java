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
        if (mixinClassName.endsWith(".compat.yat.SleepThirstSuspensionMixin")) {
            return isLoaded("yet_another_thirst");
        }
        if (mixinClassName.endsWith(".compat.dndecor.FrontlightSpotlightStateMixin")) {
            return isLoaded("dndecor");
        }
        if (mixinClassName.endsWith(".client.FrontlightBlockChangeMixin")
                || mixinClassName.endsWith(".client.VeilWrappedDepthStencilMixin")) {
            return isLoaded("sable") && isLoaded("veil") && !isLoaded("iris");
        }
        // Sable 的 vanilla 子层级区块网格需要补 Sodium 的动画贴图活跃标记；Sable 缺席时
        // 目标类不存在，mixin 不应用（Sodium 自身的动画剔除也就不影响任何子层级）。
        if (mixinClassName.endsWith(".client.SableSubLevelSpriteAnimationMixin")) {
            return isLoaded("sable");
        }
        if (mixinClassName.endsWith(".compat.connected.KineticBatteryLifecycleMixin")) {
            return isLoaded("create_connected");
        }
        // 消防水带放置/拆卸数量守恒：目标为 Create: Fire Fighting Add 的水带方块实体
        // 与放置包（该 addon 硬依赖 create，在场即蕴含 create 在场）
        if (mixinClassName.contains(".compat.firefightingadd.")) {
            return isLoaded("createfirefightingadd");
        }
        if (mixinClassName.endsWith(".compat.yat.CopperSinkBottlePurityMixin")) {
            return isLoaded("fluid") && isLoaded("yet_another_thirst");
        }
        if (mixinClassName.endsWith(".compat.yat.WaterBottleDrainPurityMixin")) {
            return isLoaded("create") && isLoaded("yet_another_thirst");
        }
        if (mixinClassName.contains(".compat.camerapture.")) {
            return isLoaded("camerapture");
        }
        if (mixinClassName.endsWith(".compat.cdg.DieselEngineRampMixin")
                || mixinClassName.endsWith(".compat.cdg.HugeDieselEngineMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgEngineTankCapacityMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgModularEngineTankCapacityMixin")
                || mixinClassName.endsWith(".compat.cdg.EngineThrottleLockMixin")
                || mixinClassName.endsWith(".compat.cdg.ModularEngineRedstoneMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgEngineGoggleFuelHintMixin")) {
            return isLoaded("createdieselgenerators");
        }
        if (mixinClassName.endsWith(".compat.cca.ElectricMotorMixin")
                || mixinClassName.endsWith(".compat.cca.ElectricMotorSoundClientMixin")
                || mixinClassName.endsWith(".compat.cca.AlternatorIdleDrainMixin")
                || mixinClassName.endsWith(".compat.cca.AlternatorOverstressLatchMixin")
                || mixinClassName.endsWith(".compat.cca.ElectricMotorGoggleMixin")
                // 目标是 Create 的 GeneratingKineticBlockEntity，但行为仅对 CCA 电动马达
                // 生效（handler instanceof 门控），故按 createaddition 在场性过滤
                || mixinClassName.endsWith(".compat.create.ElectricMotorGeneratorStatsMixin")
                // 目标是 Create 的 KineticBlockEntity，handler 以本 mod 接口 instanceof
                // 门控、仅对 CCA 交流发电机生效，同样按 createaddition 在场性过滤
                || mixinClassName.endsWith(".compat.create.AlternatorLatchSyncMixin")) {
            return isLoaded("createaddition");
        }
        // simurail 硬性依赖 sable（mods.toml required），在场性蕴含 sable 在场
        if (mixinClassName.endsWith(".compat.simurail.PhysicsBogeyBrakeSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyBrakeSignalCapMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanBrakeMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanStressMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyLateralForceMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyTrackSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyWideGaugeParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerRedstoneMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerPartnerAccessor")) {
            return isLoaded("simurail");
        }
        if (mixinClassName.endsWith(".compat.dimblend.WarpGateBlockMixin")) {
            return isLoaded("dimblend");
        }
        if (mixinClassName.endsWith(".compat.create.ItemDrainIrrigationMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainGrowthBoostMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainPipeRefillMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainHudSyncMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainGoggleMixin")
                || mixinClassName.endsWith(".compat.create.PortableEngineAttachMixin")
                || mixinClassName.endsWith(".compat.create.RotationPropagatorInvoker")
                || mixinClassName.endsWith(".compat.create.KineticNetworkUnloadedAccessor")
                || mixinClassName.endsWith(".compat.create.KineticUnloadedShareMixin")
                || mixinClassName.endsWith(".compat.create.KineticNetworkLedgerTraceMixin")
                || mixinClassName.endsWith(".compat.create.SteamEngineOverloadMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornBlockMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornBlockEntityMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornClientMixin")) {
            return isLoaded("create");
        }
        if (mixinClassName.endsWith(".compat.fluid.GutterOutletPrecipitationMixin")
                || mixinClassName.endsWith(".compat.fluid.GutterOutletBiomeProjectionMixin")
                || mixinClassName.endsWith(".compat.fluid.SmartGutterOutletBiomeProjectionMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperSinkTankMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperSinkDisplayMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperTapLeakMixin")) {
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

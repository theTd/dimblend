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
        if (mixinClassName.contains(".compat.simulated.")) {
            return isLoaded("simulated");
        }
        if (mixinClassName.contains(".compat.solcarrot.")) {
            return isLoaded("solcarrot");
        }
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
        if (mixinClassName.endsWith(".client.VeilFboStencilClearMixin")) {
            return isLoaded("sable") && isLoaded("veil") && !isLoaded("iris")
                    && classPresent("foundry.veil.impl.client.render.framebuffer.LegacyAdvancedFboImpl");
        }
        if (mixinClassName.endsWith(".compat.tacz.TaCzScopeStencilCleanupMixin")) {
            return isLoaded("tacz")
                    && classPresent("com.tacz.guns.client.model.BedrockAttachmentModel");
        }
        if (mixinClassName.endsWith(".compat.toolbelt.ToolBeltConfigBackpackGuardMixin")) {
            return isLoaded("toolbelt")
                    && classPresent("dev.gigaherz.toolbelt.ConfigData");
        }
        if (mixinClassName.endsWith(".compat.toolbelt.ToolBeltRadialMenuBackpackGuardMixin")) {
            return isLoaded("toolbelt")
                    && classPresent("dev.gigaherz.toolbelt.client.ClientEvents");
        }
        if (mixinClassName.endsWith(".compat.doublejump.DoubleJumpClientMixin")) {
            return isLoaded("double_jump_plus")
                    && classPresent("net.foufakirby.double_jump.client.DoubleJumpClient");
        }
        if (mixinClassName.endsWith(".compat.doublejump.DoubleJumpHandlerMixin")) {
            return isLoaded("double_jump_plus")
                    && classPresent("net.foufakirby.double_jump.common.DoubleJumpHandler");
        }
        if (mixinClassName.endsWith(".compat.doublejump.ServerEventsNeoForgeMixin")) {
            return isLoaded("double_jump_plus")
                    && classPresent("net.foufakirby.double_jump.neoforge.ServerEventsNeoForge");
        }
        if (mixinClassName.contains(".compat.sophisticated.")) {
            return isLoaded("sophisticatedcore")
                    && classPresent("net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase");
        }
        if (mixinClassName.contains(".compat.dirtywater.")) {
            if (!isLoaded("yet_another_thirst")) {
                return false;
            }
            if (mixinClassName.endsWith(".BasinBlockEntityDirtyWaterMixin")
                    || mixinClassName.endsWith(".BasinRecipeDirtyWaterMixin")
                    || mixinClassName.endsWith(".GenericItemEmptyingDirtyWaterMixin")
                    || mixinClassName.endsWith(".FluidHelperBasinHandDirtyWaterMixin")) {
                return isLoaded("create");
            }
            if (mixinClassName.contains(".Stockpot") || mixinClassName.contains(".Teapot")) {
                return isLoaded("kaleidoscope_cookery");
            }
            return true;
        }
        if (mixinClassName.endsWith(".compat.railways.PhantomSpriteManagerMixin")) {
            return isLoaded("railways") && isLoaded("dimblend")
                    && classPresent("com.railwayteam.railways.content.custom_tracks.phantom.PhantomSpriteManager");
        }
        // Sable 的 vanilla 子层级区块网格需要补 Sodium 的动画贴图活跃标记；Sable 缺席时
        // 目标类不存在，mixin 不应用（Sodium 自身的动画剔除也就不影响任何子层级）。
        // 同一门控的两个粒子 mixin：绘制时按 renderPose 对齐被 Sable 子层级追踪的粒子
        // （渲染坐标口的 Particle mixin 与注入 ParticleEngine#render 的 mixin 必须同进同退，
        // 否则后者会拿不到 RenderPoseShiftable）。
        if (mixinClassName.endsWith(".client.SableSubLevelSpriteAnimationMixin")
                || mixinClassName.endsWith(".client.SableSingleBlockSpriteAnimationMixin")
                || mixinClassName.endsWith(".client.SableParticleRenderPoseMixin")
                || mixinClassName.endsWith(".client.SableParticleEngineRenderPoseMixin")
                || mixinClassName.endsWith(".client.SableCritParticleInitMixin")) {
            return isLoaded("sable");
        }
        if (mixinClassName.endsWith(".compat.create.CrushingWheelControllerParticleMixin")
                || mixinClassName.endsWith(".compat.create.CrushingWheelControllerBeParticleMixin")
                || mixinClassName.endsWith(".compat.create.CrushingWheelKineticEffectParticleMixin")
                || mixinClassName.endsWith(".compat.create.CrushingWheelRotationIndicatorMixin")) {
            return isLoaded("create") && isLoaded("sable");
        }
        if (mixinClassName.endsWith(".compat.sable.SableVoidFitBlockChangeMixin")) {
            return isLoaded("sable") && classPresent("dev.ryanhcode.sable.SableCommonEvents");
        }
        if (mixinClassName.endsWith(".compat.lootjournal.LootJournalPickupRenderMixin")) {
            return isLoaded("loot_journal")
                    && classPresent("dev.obscuria.lootjournal.client.renderer.PickupComponent");
        }
        if (mixinClassName.endsWith(".compat.lootjournal.LootJournalLayoutSanitizerMixin")) {
            return isLoaded("loot_journal")
                    && classPresent("dev.obscuria.lootjournal.client.renderer.layout.LayoutParser");
        }
        if (mixinClassName.endsWith(".compat.lootjournal.LootJournalItemIconRenderMixin")) {
            return isLoaded("loot_journal")
                    && classPresent("dev.obscuria.lootjournal.client.events.ItemPickupEvent");
        }
        if (mixinClassName.endsWith(".compat.connected.KineticBatteryLifecycleMixin")
                || mixinClassName.endsWith(".compat.connected.LinkHandlerEventHitMixin")
                || mixinClassName.endsWith(".compat.connected.LinkedItemUseOverridesMixin")) {
            return isLoaded("create_connected");
        }
        // 消防水带放置/拆卸数量守恒：目标为 Create: Fire Fighting Add 的水带方块实体
        // 与放置包（该 addon 硬依赖 create，在场即蕴含 create 在场）
        if (mixinClassName.contains(".compat.firefightingadd.")) {
            return isLoaded("createfirefightingadd");
        }
        if (mixinClassName.endsWith(".compat.dragonsplus.FluidHatchDirectionMixin")) {
            return isLoaded("create_dragons_plus")
                    && classPresent("plus.dragons.createdragonsplus.common.fluids.hatch.FluidHatchBlock");
        }
        if (mixinClassName.endsWith(".compat.fluid.CopperTapCookeryFillMixin")) {
            return isLoaded("fluid") && isLoaded("kaleidoscope_cookery");
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
        if (mixinClassName.endsWith(".compat.tavern.BarrelJeiCategoryMixin")) {
            return isLoaded("kaleidoscope_tavern")
                    && isLoaded("jei")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.compat.jei.category.BarrelRecipeCategory");
        }
        if (mixinClassName.endsWith(".compat.tavern.BarrelEmiRecipeMixin")) {
            return isLoaded("kaleidoscope_tavern")
                    && isLoaded("emi")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.compat.emi.category.EmiBarrelRecipe");
        }
        if (mixinClassName.endsWith(".compat.tavern.BarrelReiCategoryMixin")) {
            return isLoaded("kaleidoscope_tavern")
                    && isLoaded("roughlyenoughitems")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.compat.rei.category.ReiBarrelRecipeCategory");
        }
        if (mixinClassName.endsWith(".compat.tavern.BarrelIngredientHandlerMixin")) {
            return isLoaded("kaleidoscope_tavern")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity$1");
        }
        if (mixinClassName.endsWith(".compat.tavern.BarrelRecipeMixin")) {
            return isLoaded("kaleidoscope_tavern")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe");
        }
        if (mixinClassName.contains(".compat.tavern.")) {
            return isLoaded("kaleidoscope_tavern")
                    && classPresent("com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity");
        }
        if (mixinClassName.endsWith(".compat.starlight.CrystalbornCatalystPlacementMixin")) {
            return isLoaded("eternal_starlight") && isLoaded("sable");
        }
        if (mixinClassName.contains(".compat.starlight.")) {
            return isLoaded("eternal_starlight")
                    && classPresent("cn.leolezury.eternalstarlight.common.block.entity.CrystalbornCatalystBlockEntity");
        }
        if (mixinClassName.endsWith(".compat.accessories.AccessoriesClientSurvivalMixin")) {
            return isLoaded("accessories")
                    && classPresent("io.wispforest.accessories.client.AccessoriesClient");
        }
        if (mixinClassName.endsWith(".compat.accessories.AccessoriesScreenOpenSurvivalMixin")) {
            return isLoaded("accessories")
                    && classPresent("io.wispforest.accessories.networking.server.ScreenOpen");
        }
        if (mixinClassName.endsWith(".compat.yat.VoidicWellPurityMixin")) {
            return isLoaded("voidscape")
                    && isLoaded("yet_another_thirst")
                    && classPresent("tamaized.voidscape.block.entity.WellBlockEntity");
        }
        if (mixinClassName.endsWith(".compat.coldsweat.ThermolithBlockModeMixin")
                || mixinClassName.endsWith(".compat.coldsweat.ThermolithBlockEntityModeMixin")
                || mixinClassName.endsWith(".compat.coldsweat.ThermolithBlockEntityAccessor")) {
            return isLoaded("cold_sweat")
                    && classPresent("com.momosoftworks.coldsweat.common.block.ThermolithBlock")
                    && classPresent("com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity");
        }
        if (mixinClassName.endsWith(".compat.cdg.DieselEngineRampMixin")
                || mixinClassName.endsWith(".compat.cdg.HugeDieselEngineMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgEngineTankCapacityMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgModularEngineTankCapacityMixin")
                || mixinClassName.endsWith(".compat.cdg.EngineThrottleLockMixin")
                || mixinClassName.endsWith(".compat.cdg.ModularEngineRedstoneMixin")
                || mixinClassName.endsWith(".compat.cdg.CdgEngineGoggleFuelHintMixin")
                || mixinClassName.endsWith(".compat.cdg.OilChunksSavedDataMixin")
                || mixinClassName.endsWith(".compat.cdg.OilScannerItemMixin")) {
            return isLoaded("createdieselgenerators");
        }
        if (mixinClassName.endsWith(".compat.cca.ElectricMotorMixin")
                || mixinClassName.endsWith(".compat.cca.ElectricMotorSoundClientMixin")
                || mixinClassName.endsWith(".compat.cca.AlternatorIdleDrainMixin")
                || mixinClassName.endsWith(".compat.cca.AlternatorCapacityMixin")
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
        // 车厢内不湿身：Cold Sweat 的 isRainingAt 用 canSeeSky 看世界方块柱，看不到
        // Sable 子层级车顶。判据复用 sable 的 SubLevelRainColumn，故除 Cold Sweat 外还要求
        // sable 在场且该版本带这个类——未打补丁的 sable 上不放行，否则 mixin 类加载即
        // NoClassDefFoundError（横切约定：单独安装本模组不崩）。
        if (mixinClassName.endsWith(".compat.coldsweat.RainWetnessShelterMixin")) {
            return isLoaded("cold_sweat") && classPresent("dev.ryanhcode.sable.util.SubLevelRainColumn");
        }
        if (mixinClassName.endsWith(".compat.simurail.PhysicsBogeyChaseBrakeMarkerMixin")) {
            return isLoaded("simurail") && isLoaded("dimblend");
        }
        if (mixinClassName.endsWith(".compat.create.StockKeeperRequestScreenOrderMixin")) {
            return isLoaded("create");
        }
        // simurail 硬性依赖 sable（mods.toml required），在场性蕴含 sable 在场
        if (mixinClassName.endsWith(".compat.simurail.PhysicsBogeyInteractionMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyBrakeSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyBrakeSignalCapMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanBrakeMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanStressMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyLateralForceMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyTrackSoundMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyWideGaugeParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.PhysicsBogeyDeadmanParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerRedstoneMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerPartnerAccessor")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerChargeMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerChargeParticleMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerWrenchMixin")
                || mixinClassName.endsWith(".compat.simurail.AutomaticCouplerTypeAccessor")) {
            return isLoaded("simurail");
        }
        if (mixinClassName.endsWith(".compat.dimblend.WarpGateBlockMixin")) {
            return isLoaded("dimblend");
        }
        // 玩偶兼任售货员（两套管理员已合并为同一入口）：目标分属 Create（SeatBlock /
        // StockTickerInteractionHandler / StockTickerBlockEntity / StockKeeperRequestScreen）与
        // Kaleidoscope Doll（DollEntityItem），两端必须同时在场。只有 Create 时 Doll 的目标类
        // 不存在，整个包跳过，避免 mixin 应用即崩。client 侧同一 mixin 同时覆盖代理坐垫与
        // 真实 DollEntity，因此也走本判据，不再有单独的 client 后缀分支。
        if (mixinClassName.contains(".compat.create.stockkeeper.")) {
            return isLoaded("create") && isLoaded("kaleidoscope_doll");
        }
        if (mixinClassName.endsWith(".compat.create.ItemDrainIrrigationMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainGrowthBoostMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainNetherEvaporationMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainPipeRefillMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainHudSyncMixin")
                || mixinClassName.endsWith(".compat.create.ItemDrainGoggleMixin")
                || mixinClassName.endsWith(".compat.create.PortableEngineAttachMixin")
                || mixinClassName.endsWith(".compat.create.RotationPropagatorInvoker")
                || mixinClassName.endsWith(".compat.create.KineticNetworkUnloadedAccessor")
                || mixinClassName.endsWith(".compat.create.KineticUnloadedShareMixin")
                || mixinClassName.endsWith(".compat.create.KineticNetworkLedgerTraceMixin")
                || mixinClassName.endsWith(".compat.create.SteamEngineOverloadMixin")
                || mixinClassName.endsWith(".compat.create.SlidingDoorBlockMixin")
                || mixinClassName.endsWith(".compat.create.SlidingDoorBlockEntityMixin")
                || mixinClassName.endsWith(".compat.create.SlidingDoorRendererMixin")
                || mixinClassName.endsWith(".compat.create.GameRendererDoorOverhangMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornBlockMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornBlockEntityMixin")
                || mixinClassName.endsWith(".compat.create.WhistleHornClientMixin")
                || mixinClassName.endsWith(".compat.create.DisplayLinkUnlimitedItemMixin")
                || mixinClassName.endsWith(".compat.create.DisplayLinkUnlimitedTargetMixin")) {
            return isLoaded("create");
        }
        if (mixinClassName.endsWith(".compat.fluid.GutterOutletPrecipitationMixin")
                || mixinClassName.endsWith(".compat.fluid.GutterOutletBiomeProjectionMixin")
                || mixinClassName.endsWith(".compat.fluid.SmartGutterOutletBiomeProjectionMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperSinkTankMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperSinkDisplayMixin")
                || mixinClassName.endsWith(".compat.fluid.CopperTapLeakMixin")
                // R13 细雪桶排空不消耗内容：目标是 fluid 的嵌套类
                // PowderSnowBucketHandler$PowderSnowBucketFluidHandler，按模组在场性过滤
                || mixinClassName.endsWith(".compat.fluid.PowderSnowBucketDrainMixin")) {
            return isLoaded("fluid");
        }
        return true;
    }

    private static boolean isLoaded(String modId) {
        return net.neoforged.fml.loading.FMLLoader.getLoadingModList().getModFileById(modId) != null;
    }

    /**
     * 目标类字节码资源是否真的存在；用于在"模组在场但版本不对"时跳过 mixin 而不是启动即崩。
     *
     * <p>严禁用 {@code Class.forName}（会在 {@code shouldApplyMixin} 阶段提前把目标类定义进
     * {@code TransformingClassLoader}，触发 {@code MixinTargetAlreadyLoadedException}），也严禁用
     * {@code IBytecodeProvider#getClassNode} 的 {@code runTransformers=false} 重载
     * （ModLauncher 的 {@code MixinLaunchPluginLegacy#getClassNode} 在不跑转换器时
     * 无条件抛 {@code IllegalArgumentException}，导致门控恒返回 {@code false}）。
     * 改走 {@code ClassLoader#getResource} 直接经 {@code ModuleClassLoader} 查 {@code .class}
     * 条目，不加载类、不跑转换器。</p>
     */
    public static boolean classPresent(String className) {
        if (className == null || className.isEmpty()) {
            return false;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (classPresent(className, contextLoader)) {
            return true;
        }
        ClassLoader pluginLoader = DimBlendMixinPlugin.class.getClassLoader();
        return pluginLoader != contextLoader && classPresent(className, pluginLoader);
    }

    public static boolean classPresent(String className, ClassLoader loader) {
        if (className == null || className.isEmpty() || loader == null) {
            return false;
        }
        return loader.getResource(className.replace('.', '/') + ".class") != null;
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

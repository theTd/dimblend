package dimblend.experience;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import dimblend.experience.exploration.ExplorationAttachments;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.create.ItemDrainPipeRefill;
import dimblend.experience.compat.simurail.TrainForceGroups;
import dimblend.experience.datagen.DataGenerators;
import dimblend.experience.gametest.CopycatObsidianHardnessGameTests;
import dimblend.experience.gametest.ItemDrainPipeRefillGameTests;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(DimBlend.MODID)
public class DimBlend {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "dimblend_experience";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public DimBlend(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(DimBlend::onRegisterGameTests);
        // Datagen（runData）：工厂切石机配方等服务端数据
        modEventBus.addListener(DataGenerators::gatherData);

        // 音效事件注册（无条件，播放侧由条件 mixin + Config 门控）
        ModSounds.SOUNDS.register(modEventBus);

        // 探索限制板块的数据附件（死亡栏位快照等）
        ExplorationAttachments.ATTACHMENTS.register(modEventBus);

        // B 板块：CDG 发动机运行态附件，仅 CDG 在场时注册
        if (ModList.get().isLoaded("createdieselgenerators")) {
            CdgAttachments.ATTACHMENTS.register(modEventBus);
        }

        // E8 横向力的 sable 力分组（Simulated 力示意图按分组绘制），条件与 E8 mixin 一致：仅 simurail 在场
        if (ModList.get().isLoaded("simurail")) {
            TrainForceGroups.FORCE_GROUPS.register(modEventBus);
        }

        // 分液池管道补水：补齐 UP 面流体接口，仅 Create 在场时注册（避免无 Create 环境类加载失败）
        if (ModList.get().isLoaded("create")) {
            modEventBus.addListener(ItemDrainPipeRefill::registerCapabilities);
        }

        // 探索规则都在服务端执行，走 SERVER 配置
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("DimBlend Experience common setup");
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        // GameTest 引用 Create 的 CopycatPanel/BE，仅 Create 在场时注册，避免无 Create 环境类加载失败
        if (ModList.get().isLoaded("create")) {
            event.register(CopycatObsidianHardnessGameTests.class);
            event.register(ItemDrainPipeRefillGameTests.class);
        }
    }
}

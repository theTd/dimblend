package dimblend.blocks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import dimblend.blocks.compat.copycats.CreativeCopycats;
import dimblend.blocks.compat.create.CreativeKinetics;
import dimblend.blocks.compat.enderstorage.TrainEnderChests;
import dimblend.blocks.compat.enderstorage.client.TrainEnderChestClient;
import dimblend.blocks.fragile.FragileStones;
import dimblend.blocks.gametest.CreativeCopycatHidingGameTest;
import dimblend.blocks.gametest.TrainEnderChestGameTest;
import dimblend.blocks.gametest.WaterBucketLavaQuenchGameTest;
import dimblend.blocks.treasure.ShellMarker;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(DimBlendBlocks.MODID)
public class DimBlendBlocks {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "dimblend_blocks";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "dimblend_blocks" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "dimblend_blocks" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public DimBlendBlocks(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);

        // 易碎石头家族：无外部依赖，常驻注册
        FragileStones.init();

        // 贝壳标记：无外部依赖，常驻注册（藏宝箱 X 标记由 dimblend-experience 摆）
        ShellMarker.init();

        // 热冰：无外部依赖的燃料物品，常驻注册
        HotIce.init();

        // C 板块：创造模式伪装方块，仅 Copycats+ 在场时注册
        // （CreativeCopycats 类内硬引用其类型，必须经 isLoaded 守卫触达）
        if (ModList.get().isLoaded("copycats")) {
            CreativeCopycats.register(modEventBus);
        }

        // K 板块：Create 动力传动件的创造模式变体，仅 Create 在场时注册
        // （CreativeKinetics 类内硬引用其类型，必须经 isLoaded 守卫触达；
        // 与 C 板块守卫独立——copycats 缺席时传动件仍应可用）
        if (ModList.get().isLoaded("create")) {
            CreativeKinetics.register(modEventBus);

            // 卸料漏斗双形态（Create 漏斗子类 + 装置内卸料行为）
            dimblend.blocks.compat.create.funnel.DumpFunnels.register(modEventBus);
            if (FMLEnvironment.dist.isClient()) {
                dimblend.blocks.compat.create.funnel.DumpFunnelClient.register(modEventBus);
                // 思索插件：仅客户端登记归属标签，不注册场景
                net.createmod.ponder.foundation.PonderIndex.addPlugin(
                        new dimblend.blocks.compat.create.DimBlendBlocksPonderPlugin());
            }

            // 卸料仪表：Deployer 的自定义 PanelType + 面板物品（两张模组都在场才注册）
            // 客户端另需在模型烘焙前登记暗钢蓝面板的 PartialModel
            if (ModList.get().isLoaded("deployer")) {
                dimblend.blocks.compat.deployer.DumpGauges.register(modEventBus);
                if (FMLEnvironment.dist.isClient()) {
                    dimblend.blocks.compat.deployer.client.DumpGaugeClient.register(modEventBus);
                }
            }
        }

        // 列车末影物流箱：仅 EnderStorage 在场时注册（硬引用 EnderStorage/CCL 类型，经 isLoaded 守卫触达）
        if (ModList.get().isLoaded("enderstorage")) {
            TrainEnderChests.register(modEventBus);
            if (FMLEnvironment.dist.isClient()) {
                TrainEnderChestClient.register(modEventBus);
            }
        }

        // 列车加压管道：需要 Create（管道/支架）与 Sable（子层级/车钩）双在场
        // （本包硬引用 Create 的 PipeBlock、Sable 的 SubLevel 与 companion 类型，必须经双重守卫触达）
        if (ModList.get().isLoaded("create") && ModList.get().isLoaded("sable")) {
            dimblend.blocks.compat.rail.TrainTubes.register(modEventBus);
            if (FMLEnvironment.dist.isClient()) {
                dimblend.blocks.compat.rail.client.TrainTubeClient.register(modEventBus);
            }
        }

        // 消防模组喷头水雾浇岩浆：仅 createfirefightingadd 在场时注册
        // （NozzleLavaQuench 硬引用消防模组 API 类型，必须经 isLoaded 守卫触达）
        if (ModList.get().isLoaded("createfirefightingadd")) {
            dimblend.blocks.compat.firefighting.NozzleLavaQuench.register();
        }

        modEventBus.addListener(DimBlendBlocks::onRegisterGameTests);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        // 水桶浇岩浆：只依赖原版与自有方块，常驻注册
        event.register(WaterBucketLavaQuenchGameTest.class);

        // C5 隐藏 GameTest：类内硬引用 CreativeCopycats（Copycats+ 类型），
        // 仅 copycats 在场（dev 下 -PwithCopycats）时注册
        if (ModList.get().isLoaded("copycats")) {
            event.register(CreativeCopycatHidingGameTest.class);
        }

        // 列车末影物流箱 GameTest：类内硬引用 EnderStorage、Create 与 Sable 类型，三者均在场（dev 下 -PwithEnderStorage）时注册
        if (ModList.get().isLoaded("enderstorage")
                && ModList.get().isLoaded("create")
                && ModList.get().isLoaded("sable")) {
            event.register(TrainEnderChestGameTest.class);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("DimBlend Blocks common setup");
    }
}

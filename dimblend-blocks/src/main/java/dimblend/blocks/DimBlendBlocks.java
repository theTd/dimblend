package dimblend.blocks;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import dimblend.blocks.compat.copycats.CreativeCopycats;
import dimblend.blocks.fragile.FragileStones;

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

        // C 板块：创造模式伪装方块，仅 Copycats+ 在场时注册
        // （CreativeCopycats 类内硬引用其类型，必须经 isLoaded 守卫触达）
        if (ModList.get().isLoaded("copycats")) {
            CreativeCopycats.register(modEventBus);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("DimBlend Blocks common setup");
    }
}

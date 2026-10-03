package dimblend.carwash;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dimblend.carwash.chassis.ChassisBehaviourBinding;
import dimblend.carwash.compat.firefighting.NozzleSprayWashing;
import dimblend.carwash.server.ChassisHandInteractions;
import dimblend.carwash.server.ChassisSprayWashQueue;
import dimblend.carwash.server.ChassisTravelGrime;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 车辆脏污与清洗：车架方块（见 {@link dimblend.carwash.chassis.ChassisBlocks}）随车厢行驶积灰，
 * 水桶/湿海绵/下雨/消防喷淋清洗，纯视觉。
 */
@Mod(DimBlendCarwash.MODID)
public class DimBlendCarwash {
    public static final String MODID = "dimblend_carwash";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** Create: FireFighting Additions 的 mod id（可选依赖）。 */
    private static final String FIREFIGHTING_MODID = "createfirefightingadd";

    public DimBlendCarwash(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(ChassisBehaviourBinding.class);
        NeoForge.EVENT_BUS.register(ChassisTravelGrime.class);
        NeoForge.EVENT_BUS.register(ChassisHandInteractions.class);
        // NozzleSprayWashing 实现喷淋 mod 的接口，只能在其在场时触达；批处理队列随之启用
        if (ModList.get().isLoaded(FIREFIGHTING_MODID)) {
            NozzleSprayWashing.register();
            NeoForge.EVENT_BUS.register(ChassisSprayWashQueue.class);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

package dimblend.radio;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dimblend.radio.net.RadioNetwork;
import dimblend.radio.server.RadioSync;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(DimBlendRadio.MODID)
public class DimBlendRadio {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "dimblend_radio";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimBlendRadio(IEventBus modEventBus) {
        RadioNetwork.register(modEventBus);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(RadioSync.class);
        RadioCatalog.scanServerAsync();
    }
}

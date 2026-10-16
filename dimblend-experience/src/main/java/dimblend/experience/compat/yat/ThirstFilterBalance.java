package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.core.thirst.ThirstConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** YAT 1.5.2 exposes these filter settings as common constants, not config values. */
public final class ThirstFilterBalance {
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ThirstConfig.FABRIC_FILTER_MAX_PURITY = 1;
            ThirstConfig.SAND_FILTER_MAX_PURITY = 1;
            ThirstConfig.CARBON_FILTER_MAX_PURITY = 1;
            ThirstConfig.FABRIC_FILTER_DURABILITY /= 2;
            ThirstConfig.CARBON_FILTER_DURABILITY *= 2;
        });
    }

    private ThirstFilterBalance() { }
}

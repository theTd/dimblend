package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.SimurailBlockEntities;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import dimblend.experience.DimBlend;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Resolve this class only while both Create and Simurail are installed. */
public final class TractionDisplaySources {
    private static final DeferredRegister<DisplaySource> SOURCES =
            DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, DimBlend.MODID);
    public static final DeferredHolder<DisplaySource, TractionEfficiencyDisplaySource> TRACTION_EFFICIENCY =
            SOURCES.register("traction_efficiency", TractionEfficiencyDisplaySource::new);

    public static void register(IEventBus modEventBus) {
        SOURCES.register(modEventBus);
        modEventBus.addListener(TractionDisplaySources::setup);
    }

    private static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> DisplaySource.BY_BLOCK_ENTITY.add(
                SimurailBlockEntities.PHYSICS_BOGEY.get(), TRACTION_EFFICIENCY.get()));
    }

    private TractionDisplaySources() { }
}

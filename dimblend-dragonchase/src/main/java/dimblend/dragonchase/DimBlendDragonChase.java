package dimblend.dragonchase;

import com.mojang.logging.LogUtils;
import dimblend.dragonchase.carriage.CarriageBootstrap;
import dimblend.dragonchase.chase.ChaseBootstrap;
import dimblend.dragonchase.client.DragonChaseClient;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Dragon Chase: the endgame chase. Requirements: {@code docs/requirements.md}; plan: {@code docs/plan.md}.
 *
 * <p>Wiring only. Each package owns its own registration:
 * {@link DragonChaseRegistries} (entity types), {@link CarriageBootstrap} (car behaviour events),
 * {@link ChaseBootstrap} (director, saved data, commands, spawn rules) and, on the client,
 * {@link DragonChaseClient} (renderers).
 */
@Mod(DimBlendDragonChase.MODID)
public class DimBlendDragonChase {
    public static final String MODID = "dimblend_dragonchase";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DimBlendDragonChase(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        DragonChaseRegistries.register(modEventBus);
        CarriageBootstrap.init(modEventBus);
        ChaseBootstrap.init(modEventBus);
        // GameTests live behind their own fixture (-PwithDragonChaseGameTest): the run sets this
        // property, so a normal build/run never registers a suite that would need a physics stack.
        if (Boolean.getBoolean("dimblend.dragonchase.gametest")) {
            modEventBus.addListener(dimblend.dragonchase.gametest.DragonChaseGameTests::register);
        }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DragonChaseClient.init(modEventBus);
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}

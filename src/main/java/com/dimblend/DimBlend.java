package com.dimblend;

import com.dimblend.command.DimBlendCommands;
import com.dimblend.compat.TerraBlenderRotatingCompat;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("dimblend")
public final class DimBlend {
    public DimBlend(IEventBus modBus) {
        DimBlendRegistries.register(modBus);
        NeoForge.EVENT_BUS.addListener(DimBlend::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, TerraBlenderRotatingCompat::onServerAboutToStart);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        DimBlendCommands.register(event.getDispatcher());
    }
}

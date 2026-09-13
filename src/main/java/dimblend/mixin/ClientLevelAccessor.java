package dimblend.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Direct write access to the client level data, bypassing NeoForge's patched ClientLevel.setDayTime (which force-enables the doDaylightCycle gamerule as a side effect). */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
    @Accessor("clientLevelData")
    ClientLevel.ClientLevelData dimblend$getClientLevelData();
}

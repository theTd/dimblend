package dimblend.radio.mixin.client;

import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("executor")
    SoundEngineExecutor dimblend$radioExecutor();

    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> dimblend$radioChannels();

    @Invoker("calculateVolume")
    float dimblend$radioVolume(SoundInstance instance);
}

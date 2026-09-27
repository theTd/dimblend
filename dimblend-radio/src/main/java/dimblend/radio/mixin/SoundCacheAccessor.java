package dimblend.radio.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

/**
 * 取 SoundManager.soundCache 做免打断注入（mixin 点 B-(1)）。
 *
 * <p>原理（源码事实）：{@code SoundManager.<init>} 用
 * {@code ResourceProvider.fromMap(this.soundCache)} 构造 SoundEngine 的 provider；
 * fromMap 实现为按调用实时查 Map（非快照），后续 put 的合成 Resource 立即可见。
 * registry 不动：RadioInstance 覆写 resolve() 自带 WeighedSoundEvents 旁路。</p>
 */
@Mixin(net.minecraft.client.sounds.SoundManager.class)
public interface SoundCacheAccessor {
    @Accessor("soundCache")
    Map<ResourceLocation, Resource> dimblend$getSoundCache();
}

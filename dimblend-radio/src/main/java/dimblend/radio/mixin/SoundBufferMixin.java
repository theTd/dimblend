package dimblend.radio.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dimblend.radio.client.RadioAudioStream;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.ResourceLocation;

/**
 * 解码分流：radio 合成路径（{@code dimblend_radio:sounds/radio/*.ogg}）不走 JOrbis，
 * 改由 {@link RadioAudioStream} 供给内存 PCM 流；其余原版路径零改动。
 *
 * <p>为什么必须分流：{@code SoundBufferLibrary.getStream/getCompleteBuffer}
 * 写死 {@code new JOrbisAudioStream}（源码事实），内存 PCM 冒充 .ogg 字节必抛
 * “Invalid Ogg file”。纯 Java Vorbis 编码器无可用 Maven 坐标，
 * 转码 Ogg 路已否决。</p>
 */
@Mixin(net.minecraft.client.sounds.SoundBufferLibrary.class)
public abstract class SoundBufferMixin {
    @Shadow
    @Final
    private java.util.Map<ResourceLocation, java.util.concurrent.CompletableFuture<com.mojang.blaze3d.audio.SoundBuffer>> cache;

    @Inject(method = "getStream(Lnet/minecraft/resources/ResourceLocation;Z)"
            + "Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true)
    private void dimblend$radioStream(ResourceLocation path, boolean looping,
            CallbackInfoReturnable<CompletableFuture<AudioStream>> cir) {
        AudioStream feed = RadioAudioStream.open(path);
        if (feed != null) {
            cir.setReturnValue(CompletableFuture.completedFuture(feed));
        }
    }

    @Inject(method = "getCompleteBuffer(Lnet/minecraft/resources/ResourceLocation;)"
            + "Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"), cancellable = true)
    private void dimblend$radioStaticBuffer(ResourceLocation path,
            CallbackInfoReturnable<CompletableFuture<com.mojang.blaze3d.audio.SoundBuffer>> cir) {
        // 短曲 static 路径：radio 一律 stream:true，理论上走不到；保险起见仍分流
        AudioStream feed = RadioAudioStream.open(path);
        if (feed != null) {
            CompletableFuture<com.mojang.blaze3d.audio.SoundBuffer> future = CompletableFuture.supplyAsync(() -> {
                try {
                    java.nio.ByteBuffer buf = ((net.minecraft.client.sounds.FiniteAudioStream) feed).readAll();
                    return new com.mojang.blaze3d.audio.SoundBuffer(buf, feed.getFormat());
                } catch (java.io.IOException e) {
                    throw new CompletionException(e);
                }
            }, net.minecraft.Util.nonCriticalIoPool());
            cir.setReturnValue(future);
        }
    }
}

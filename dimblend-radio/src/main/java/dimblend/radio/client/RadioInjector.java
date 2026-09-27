package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * 合成事件装配：{@link RadioInstance#resolve} 自带，不进 registry。
 *
 * <p>path 形态 = {@code Sound.getPath()} = {@code sounds/radio/<hash>.ogg}
 * （命名空间 dimblend_radio），{@code SoundBufferMixin} 按此 path 从
 * {@link RadioPcmFeed} 取 PCM 分流，全程不碰 registry、不 reload。</p>
 */
public final class RadioInjector {
    /**
     * 自然衰减到零的参考半径：64 格（原版唱片机 f1 = 4.0 × 16 同值）。
     * 32 格是“半音参考点”（gain ≈ 0.5），不是截止点；LINEAR_DISTANCE 下
     * gain = 1 - d/64，到 64 格才真正归零。音乐压制看真零点，不看 32 格。
     */
    public static final int RANGE_BLOCKS = 64;

    public static Sound makeSound(String hash) {
        return new Sound(
                ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio/" + hash),
                ConstantFloat.of(1.0f),
                ConstantFloat.of(1.0f),
                1,
                Sound.Type.FILE,
                true, // stream：长曲必需（static 全量常驻内存，stream 恒定 4×1s 泵）
                false,
                RANGE_BLOCKS);
    }

    public static WeighedSoundEvents makeEvent(String hash) {
        WeighedSoundEvents events = new WeighedSoundEvents(
                ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "radio/" + hash), null);
        events.addSound(makeSound(hash));
        return events;
    }

    private RadioInjector() {
    }
}

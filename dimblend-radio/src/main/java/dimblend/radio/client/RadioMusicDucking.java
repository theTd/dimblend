package dimblend.radio.client;

import dimblend.radio.DimBlendRadio;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;

/** One shared fade for background music, including tracks owned by mod music managers. */
public final class RadioMusicDucking {
    private static final TagKey<SoundEvent> EXEMPT = TagKey.create(Registries.SOUND_EVENT,
            ResourceLocation.fromNamespaceAndPath(DimBlendRadio.MODID, "music_ducking_exempt"));
    private static final RadioMusicFade FADE = new RadioMusicFade();
    private static boolean suppressing;

    /** Called once per unpaused sound-engine tick; includes the final volume-restoration tick. */
    public static boolean tick(boolean radioAudible) {
        float previous = FADE.gain();
        suppressing = radioAudible;
        float gain = FADE.tick(radioAudible);
        return gain != previous || gain < 1.0f;
    }

    public static boolean suppressing() {
        return suppressing;
    }

    public static float gain() {
        return FADE.gain();
    }

    public static boolean affects(SoundInstance sound) {
        return sound.getSource() == SoundSource.MUSIC && !BuiltInRegistries.SOUND_EVENT
                .getHolder(sound.getLocation()).map(holder -> holder.is(EXEMPT)).orElse(false);
    }

    public static float volume(SoundInstance sound, float normalVolume) {
        return scale(normalVolume, sound.getSource(), !affects(sound));
    }

    static float scale(float normalVolume, SoundSource source, boolean exempt) {
        return source == SoundSource.MUSIC && !exempt ? normalVolume * FADE.gain() : normalVolume;
    }

    public static void reset() {
        suppressing = false;
        FADE.reset();
    }

    private RadioMusicDucking() { }
}

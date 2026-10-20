package dimblend.radio.client;

import net.minecraft.sounds.SoundSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioMusicDuckingTest {
    @BeforeEach @AfterEach
    void reset() {
        RadioMusicDucking.reset();
    }

    @Test
    void sharedFadeKeepsTickingModMusicSilentDespiteItsOwnVolumeChanges() {
        for (int tick = 0; tick < 20; tick++) RadioMusicDucking.tick(true);
        for (float modVolume : new float[] {0.1f, 0.5f, 1.0f}) {
            assertEquals(0.0f, RadioMusicDucking.scale(modVolume, SoundSource.MUSIC, false));
        }
        assertTrue(RadioMusicDucking.suppressing());
    }

    @Test
    void musicStartedDuringTakeoverUsesTheExistingEnvelope() {
        for (int tick = 0; tick < 10; tick++) RadioMusicDucking.tick(true);
        assertEquals(0.3f, RadioMusicDucking.scale(0.6f, SoundSource.MUSIC, false), 1.0e-6f);
        assertEquals(0.1f, RadioMusicDucking.scale(0.2f, SoundSource.MUSIC, false), 1.0e-6f);
        for (int tick = 0; tick < 10; tick++) RadioMusicDucking.tick(true);
        assertEquals(0.0f, RadioMusicDucking.scale(1.0f, SoundSource.MUSIC, false));
    }

    @Test
    void recoveryMultipliesTheCurrentModFadeAndSliderInsteadOfRestoringAnOldVolume() {
        for (int tick = 0; tick < 10; tick++) RadioMusicDucking.tick(true);
        RadioMusicDucking.tick(false);
        assertEquals(0.22f, RadioMusicDucking.scale(0.4f, SoundSource.MUSIC, false), 1.0e-6f);
        for (int tick = 0; tick < 9; tick++) RadioMusicDucking.tick(false);
        assertEquals(0.4f, RadioMusicDucking.scale(0.4f, SoundSource.MUSIC, false));
        assertFalse(RadioMusicDucking.suppressing());
    }

    @Test
    void radioRecordsEffectsAndExemptAchievementJinglesKeepTheirVolume() {
        for (int tick = 0; tick < 20; tick++) RadioMusicDucking.tick(true);
        for (SoundSource source : SoundSource.values()) {
            if (source != SoundSource.MUSIC) {
                assertEquals(0.7f, RadioMusicDucking.scale(0.7f, source, false));
            }
        }
        assertEquals(0.7f, RadioMusicDucking.scale(0.7f, SoundSource.MUSIC, true));
    }

    @Test
    void finalRecoveryTickIsRefreshedAndReloadResetsTheEnvelope() {
        RadioMusicDucking.tick(true);
        assertTrue(RadioMusicDucking.tick(false));
        assertEquals(1.0f, RadioMusicDucking.gain());
        assertFalse(RadioMusicDucking.tick(false));
        for (int tick = 0; tick < 20; tick++) RadioMusicDucking.tick(true);
        RadioMusicDucking.reset();
        assertEquals(1.0f, RadioMusicDucking.gain());
        assertFalse(RadioMusicDucking.suppressing());
    }
}

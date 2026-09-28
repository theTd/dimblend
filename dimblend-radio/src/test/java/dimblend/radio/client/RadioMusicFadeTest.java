package dimblend.radio.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadioMusicFadeTest {
    @Test
    void takeoverFadesInsteadOfStoppingOnFirstTick() {
        RadioMusicFade fade = new RadioMusicFade();
        assertEquals(0.95f, fade.tick(true));
        for (int i = 1; i < 19; i++) {
            assertTrue(fade.tick(true) > 0);
        }
        assertEquals(0, fade.tick(true));
        assertEquals(0, fade.tick(true));
    }

    @Test
    void radioStoppingMidFadeRestoresSmoothly() {
        RadioMusicFade fade = new RadioMusicFade();
        for (int i = 0; i < 10; i++) {
            fade.tick(true);
        }
        assertEquals(0.5f, fade.gain());
        assertEquals(0.55f, fade.tick(false));
        for (int i = 0; i < 30; i++) {
            fade.tick(false);
        }
        assertEquals(1, fade.gain());
    }

    @Test
    void replacementMusicStartsItsOwnFadeAtFullGain() {
        RadioMusicFade fade = new RadioMusicFade();
        for (int i = 0; i < 20; i++) {
            fade.tick(true);
        }
        fade.reset();
        assertEquals(1, fade.gain());
        assertEquals(0.95f, fade.tick(true));
    }
}

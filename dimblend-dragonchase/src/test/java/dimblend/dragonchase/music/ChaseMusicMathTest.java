package dimblend.dragonchase.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Phase B threshold, the two fade curves, the Phase End author mapping and the asset
 * declarations, without a client.
 *
 * <p>The threshold is the only place the "first drop strictly below 50% HP" rule lives, the
 * curves are the only place the 4 s crossfade and the 16 s end fade live, and the Phase End table
 * is the only place the "victory track has the fight's author" rule lives; all three are exercised
 * here rather than in a live game, where a pause, a dropped payload or a missing track would hide a
 * regression.
 */
class ChaseMusicMathTest {
    @Test
    void phaseBStartsStrictlyBelowHalfHealth() {
        float maxHealth = 400.0F;
        // Exactly half is the boundary and must not trigger.
        assertFalse(ChaseMusicMath.phaseBReached(200.0F, maxHealth));
        // One tick of damage below the boundary does.
        assertTrue(ChaseMusicMath.phaseBReached(199.9F, maxHealth));
        // Any health at or below zero is a kill, not a phase change: the chase is ending.
        assertFalse(ChaseMusicMath.phaseBReached(0.0F, maxHealth));
        assertFalse(ChaseMusicMath.phaseBReached(-1.0F, maxHealth));
        // A degenerate max (unbound attribute) must not divide the world into "always Phase B".
        assertFalse(ChaseMusicMath.phaseBReached(10.0F, 0.0F));
    }

    @Test
    void phaseBFiresOnAScaledHealthPool() {
        // Two players: (400 + 200) = 600 max health, so the drop is at 300.
        assertFalse(ChaseMusicMath.phaseBReached(300.0F, 600.0F));
        assertTrue(ChaseMusicMath.phaseBReached(299.0F, 600.0F));
    }

    @Test
    void phaseBTriggersOnceAndNeverAgainAfterCrystalHealing() {
        // First drop: the chase is active, the latch is clear, the dragon is below half.
        assertTrue(ChaseMusicMath.shouldTriggerPhaseB(false, true, 199.0F, 400.0F));
        // Crystal healing pushes it back above half, then damage drops it again: the latch holds.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(true, true, 350.0F, 400.0F));
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(true, true, 199.0F, 400.0F));
        // No chase, no music transition, even below half.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, false, 199.0F, 400.0F));
        // The killing blow is not "below 50%": health is zero and the chase is ending.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, true, 0.0F, 400.0F));
        // Exactly half is not below half.
        assertFalse(ChaseMusicMath.shouldTriggerPhaseB(false, true, 200.0F, 400.0F));
    }

    @Test
    void fadeOutReachesZeroAtTheSixteenSecondMark() {
        assertEquals(320, ChaseMusicMath.END_FADE_TICKS);
        assertEquals(1.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 0, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.5F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 160, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, ChaseMusicMath.END_FADE_TICKS,
                ChaseMusicMath.END_FADE_TICKS));
        // A fade that overshoots its length stays at the target instead of going negative.
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 900, ChaseMusicMath.END_FADE_TICKS));
    }

    @Test
    void crossfadeRunsOverFourSecondsFromTheCurrentVolume() {
        assertEquals(80, ChaseMusicMath.CROSSFADE_TICKS);
        // Starting mid-fade (a kill during the crossfade) retargets from the current volume.
        assertEquals(0.5F, ChaseMusicMath.fadeGain(0.5F, 0.0F, 0, ChaseMusicMath.END_FADE_TICKS));
        assertEquals(0.25F, ChaseMusicMath.fadeGain(0.5F, 0.0F, 160, ChaseMusicMath.END_FADE_TICKS));
        // Fading in: the first tick is above zero so the engine creates the channel immediately.
        assertTrue(ChaseMusicMath.SILENT_START_VOLUME > 0.0F);
        assertEquals(ChaseMusicMath.SILENT_START_VOLUME,
                ChaseMusicMath.fadeGain(ChaseMusicMath.SILENT_START_VOLUME, 1.0F, 0, ChaseMusicMath.CROSSFADE_TICKS));
        assertEquals(1.0F, ChaseMusicMath.fadeGain(ChaseMusicMath.SILENT_START_VOLUME, 1.0F,
                ChaseMusicMath.CROSSFADE_TICKS, ChaseMusicMath.CROSSFADE_TICKS));
    }

    @Test
    void fadeGainClampsToTheEngineRange() {
        assertEquals(1.0F, ChaseMusicMath.fadeGain(2.0F, 3.0F, 10, 80));
        assertEquals(0.0F, ChaseMusicMath.fadeGain(-1.0F, -0.5F, 10, 80));
        // A zero-length fade applies its target on the spot.
        assertEquals(0.0F, ChaseMusicMath.fadeGain(1.0F, 0.0F, 0, 0));
    }

    @Test
    void trackIndexIsClampedIntoTheFourOnDiskTracks() {
        assertEquals(4, ChaseMusicMath.TRACK_COUNT);
        assertEquals(0, ChaseMusicMath.clampTrack(-3));
        assertEquals(0, ChaseMusicMath.clampTrack(0));
        assertEquals(3, ChaseMusicMath.clampTrack(3));
        assertEquals(3, ChaseMusicMath.clampTrack(4));
        assertEquals(3, ChaseMusicMath.clampTrack(400));
    }

    @Test
    void phaseEnumIsIdleByDefaultAndOutOfRangeSafe() {
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(0));
        assertEquals(ChaseMusicPhase.PHASE_A, ChaseMusicPhase.byOrdinal(1));
        assertEquals(ChaseMusicPhase.PHASE_B, ChaseMusicPhase.byOrdinal(2));
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(-1));
        assertEquals(ChaseMusicPhase.IDLE, ChaseMusicPhase.byOrdinal(99));
        assertFalse(ChaseMusicPhase.IDLE.isAudible());
        assertTrue(ChaseMusicPhase.PHASE_A.isAudible());
        assertTrue(ChaseMusicPhase.PHASE_B.isAudible());
    }

    @Test
    void phaseEndIsAudibleOnceAndNeverLoops() {
        // The wire carries the ordinal, so the new phase's position is part of the contract.
        assertEquals(3, ChaseMusicPhase.PHASE_END.ordinal());
        assertEquals(4, ChaseMusicPhase.values().length);
        assertEquals(ChaseMusicPhase.PHASE_END, ChaseMusicPhase.byOrdinal(3));
        // Still a track the client must play (only IDLE is inaudible) ...
        assertTrue(ChaseMusicPhase.PHASE_END.isAudible());
        // ... but a single pass, unlike the two fight tracks.
        assertFalse(ChaseMusicPhase.PHASE_END.isLooping());
        assertTrue(ChaseMusicPhase.PHASE_A.isLooping());
        assertTrue(ChaseMusicPhase.PHASE_B.isLooping());
        assertFalse(ChaseMusicPhase.IDLE.isLooping());
    }

    @Test
    void phaseEndTrackMapsOneToOneOntoThePhaseAAuthors() {
        // Index n of Phase A and index n of Phase End are the same composer (0..3).
        for (int index = 0; index < ChaseMusicMath.TRACK_COUNT; index++) {
            assertEquals(index, ChaseMusicMath.phaseEndTrack(index));
        }
        // A chase that never rolled its Phase A track (-1) and an out-of-range index both clamp into
        // the four on-disk files instead of pointing off the table.
        assertEquals(0, ChaseMusicMath.phaseEndTrack(-1));
        assertEquals(0, ChaseMusicMath.phaseEndTrack(-40));
        assertEquals(3, ChaseMusicMath.phaseEndTrack(4));
        assertEquals(3, ChaseMusicMath.phaseEndTrack(400));
    }

    @Test
    void phaseEndTrackPathsUseThePhaseEndFileStem() {
        for (int index = 0; index < ChaseMusicMath.TRACK_COUNT; index++) {
            assertEquals("music.phase_end_" + (index + 1),
                    ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_END, index));
        }
        // Same clamping as the fight tables.
        assertEquals("music.phase_end_1", ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_END, -1));
        assertEquals("music.phase_end_4", ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_END, 9));
        // And the older prefixes must not have been reshuffled by the new phase.
        assertEquals("music.phase_a_1", ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_A, 0));
        assertEquals("music.phase_a_4", ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_A, 3));
        assertEquals("music.phase_b_4", ChaseMusicMath.trackPath(ChaseMusicPhase.PHASE_B, 3));
    }

    @Test
    void phaseEndSoundEntriesStreamLikeTheFightTracks() throws IOException {
        JsonObject sounds = readSoundsJson();
        for (int index = 1; index <= ChaseMusicMath.TRACK_COUNT; index++) {
            String event = "music.phase_end_" + index;
            assertTrue(sounds.has(event), "sounds.json 缺事件 " + event);
            JsonObject first = sounds.getAsJsonObject(event).getAsJsonArray("sounds").get(0).getAsJsonObject();
            assertEquals("dimblend_dragonchase:music/phase_end_" + index, first.get("name").getAsString(),
                    event + " 指向的文件名不对");
            assertTrue(first.has("stream") && first.get("stream").getAsBoolean(),
                    event + " 必须 stream: true（文件是分钟级长曲，不能整段解码）");
            assertEquals(1, sounds.getAsJsonObject(event).getAsJsonArray("sounds").size(),
                    event + " 只能有一个声音，否则每个客户端会各自随机挑一首");
        }
    }

    @Test
    void phaseEndOggFilesExistAndAreNotEmpty() throws IOException {
        JsonObject sounds = readSoundsJson();
        for (int index = 1; index <= ChaseMusicMath.TRACK_COUNT; index++) {
            JsonObject first = sounds.getAsJsonObject("music.phase_end_" + index)
                    .getAsJsonArray("sounds").get(0).getAsJsonObject();
            // "dimblend_dragonchase:music/phase_end_1" -> assets/<ns>/sounds/music/phase_end_1.ogg
            String[] parts = first.get("name").getAsString().split(":", 2);
            Path ogg = resourcePath("assets/" + parts[0] + "/sounds/" + parts[1] + ".ogg");
            assertTrue(Files.isRegularFile(ogg), "缺音频文件 " + ogg);
            assertTrue(Files.size(ogg) > 0L, "音频文件是空的 " + ogg);
        }
    }

    /** {@code assets/dimblend_dragonchase/sounds.json} 解析成对象（不经过资源重载，纯文件校验）。 */
    private static JsonObject readSoundsJson() throws IOException {
        try (InputStream stream = Files.newInputStream(resourcePath("assets/dimblend_dragonchase/sounds.json"))) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /**
     * 资源绝对路径。优先 {@code build/resources/main} 那份（Gradle 测试工作目录无论在哪都能用），
     * 退到源码树 {@code src/main/resources}（IDE 里单跑一个测试类时也能读到）。
     */
    private static Path resourcePath(String relative) {
        URL url = ChaseMusicMathTest.class.getClassLoader().getResource(relative);
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI());
            } catch (URISyntaxException malformed) {
                throw new AssertionError("资源 URL 无法转成路径: " + url, malformed);
            }
        }
        Path start = Path.of("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            for (Path root : List.of(dir.resolve("src/main/resources"),
                    dir.resolve("dimblend-dragonchase").resolve("src/main/resources"))) {
                Path candidate = root.resolve(relative);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        throw new AssertionError("找不到资源 " + relative + "（classpath 与源码树都没有，起点 " + start + "）");
    }
}

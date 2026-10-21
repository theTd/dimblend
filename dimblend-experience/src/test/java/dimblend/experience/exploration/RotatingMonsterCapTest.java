package dimblend.experience.exploration;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RotatingMonsterCapTest {

    // MobCategory.MONSTER 的每区块上限，mcsrc-1.21.1 net/minecraft/world/entity/MobCategory.java:7 核实
    private static final int MONSTER_CAP = 70;

    @Test
    void fullRateMatchesVanillaFormula() {
        for (int chunkCount : new int[] {0, 1, 100, 289, 578, 1000}) {
            assertEquals(MONSTER_CAP * chunkCount / 289,
                    RotatingMonsterCap.scaledCap(MONSTER_CAP, chunkCount, 1.0D),
                    "chunkCount " + chunkCount);
        }
    }

    @Test
    void halfRateHalvesPerChunkCapBeforeVanillaTruncation() {
        // 默认 0.5：35×289/289=35（满环 35 只，原版 70 只）
        assertEquals(35, RotatingMonsterCap.scaledCap(MONSTER_CAP, 289, 0.5D));
        // 部分区块：35×100/289=12（原版 70×100/289=24）
        assertEquals(12, RotatingMonsterCap.scaledCap(MONSTER_CAP, 100, 0.5D));
    }

    @Test
    void zeroRateBansAllNaturalMonsterSpawns() {
        assertEquals(0, RotatingMonsterCap.scaledCap(MONSTER_CAP, 289, 0.0D));
    }

    @Test
    void capIsMonotonicInMultiplierAndChunkCount() {
        int previous = -1;
        for (int percent = 0; percent <= 100; percent++) {
            int cap = RotatingMonsterCap.scaledCap(MONSTER_CAP, 289, percent / 100.0D);
            assertTrue(cap >= previous, "multiplier " + percent + "%");
            assertTrue(cap >= 0 && cap <= MONSTER_CAP);
            previous = cap;
        }
        for (int chunkCount = 0; chunkCount <= 1156; chunkCount++) {
            assertTrue(RotatingMonsterCap.scaledCap(MONSTER_CAP, chunkCount, 0.5D)
                    >= RotatingMonsterCap.scaledCap(MONSTER_CAP, Math.max(0, chunkCount - 1), 0.5D));
        }
    }
}

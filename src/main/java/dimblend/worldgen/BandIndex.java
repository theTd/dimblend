package dimblend.worldgen;

import net.minecraft.world.level.ChunkPos;

public final class BandIndex {
    public static final int DEFAULT_BAND_SIZE = 4096;
    public static final int SEAM_WIDTH = 32;
    public static final int OVERWORLD_BAND = 0;

    private BandIndex() {
    }

    public static int ofBlockX(int blockX, int bandSize, int bandCount) {
        return Math.floorMod(Math.floorDiv(blockX, bandSize), bandCount);
    }

    public static int ofChunk(ChunkPos pos, int bandSize, int bandCount) {
        return ofBlockX(pos.getMinBlockX(), bandSize, bandCount);
    }

    public static int ofQuartX(int quartX, int bandSize, int bandCount) {
        return ofBlockX(quartX << 2, bandSize, bandCount);
    }


    public static int twilightBand(int bandCount) {
        return bandCount - 1;
    }

    public static int signedDistanceToOverworldTwilightSeam(int blockX, int bandSize, int bandCount) {
        if (bandCount < 2 || bandSize < SEAM_WIDTH) {
            return Integer.MAX_VALUE;
        }
        int twilightBand = twilightBand(bandCount);
        int index = ofBlockX(blockX, bandSize, bandCount);
        int local = Math.floorMod(blockX, bandSize);
        if (index == twilightBand && local >= bandSize - SEAM_WIDTH) {
            return local - bandSize;
        }
        if (index == OVERWORLD_BAND && local < SEAM_WIDTH) {
            return local;
        }
        return Integer.MAX_VALUE;
    }

    public static boolean isOverworldTwilightSeam(int blockX, int bandSize, int bandCount) {
        int signed = signedDistanceToOverworldTwilightSeam(blockX, bandSize, bandCount);
        return signed >= -SEAM_WIDTH && signed < SEAM_WIDTH;
    }

    public static boolean chunkTouchesOverworldTwilightSeam(ChunkPos pos, int bandSize, int bandCount) {
        return isOverworldTwilightSeam(pos.getMinBlockX(), bandSize, bandCount)
                || isOverworldTwilightSeam(pos.getMaxBlockX(), bandSize, bandCount);
    }

    public static float overworldWeightAcrossTwilightSeam(int blockX, int bandSize, int bandCount) {
        int signed = signedDistanceToOverworldTwilightSeam(blockX, bandSize, bandCount);
        if (signed == Integer.MAX_VALUE || signed < -SEAM_WIDTH || signed >= SEAM_WIDTH) {
            return ofBlockX(blockX, bandSize, bandCount) == OVERWORLD_BAND ? 1.0f : 0.0f;
        }
        float u = (signed + SEAM_WIDTH) / (SEAM_WIDTH * 2.0f);
        u = Math.max(0.0f, Math.min(1.0f, u));
        return u * u * (3.0f - 2.0f * u);
    }
}

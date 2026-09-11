package dimblend.worldgen;

import com.mojang.serialization.Codec;

public enum OverworldSlice {
    SURFACE("surface", 0, 32, 320, true, false),
    UNDERGROUND("underground", 64, -64, 32, true, true);

    public static final Codec<OverworldSlice> CODEC = Codec.STRING.xmap(OverworldSlice::byName, OverworldSlice::serializedName);

    private final String serializedName;
    private final int yOffset;
    private final int sourceMinY;
    private final int sourceMaxExclusiveY;
    private final boolean bottomBedrock;
    private final boolean topBedrock;

    OverworldSlice(
            String serializedName,
            int yOffset,
            int sourceMinY,
            int sourceMaxExclusiveY,
            boolean bottomBedrock,
            boolean topBedrock
    ) {
        this.serializedName = serializedName;
        this.yOffset = yOffset;
        this.sourceMinY = sourceMinY;
        this.sourceMaxExclusiveY = sourceMaxExclusiveY;
        this.bottomBedrock = bottomBedrock;
        this.topBedrock = topBedrock;
    }

    public String serializedName() {
        return this.serializedName;
    }

    public int yOffset() {
        return this.yOffset;
    }

    public int sourceMinY() {
        return this.sourceMinY;
    }

    public int sourceMaxExclusiveY() {
        return this.sourceMaxExclusiveY;
    }
    public boolean bottomBedrock() {
        return this.bottomBedrock;
    }

    public boolean topBedrock() {
        return this.topBedrock;
    }

    public int toSourceY(int targetY) {
        return targetY - this.yOffset;
    }

    public int toTargetY(int sourceY) {
        return sourceY + this.yOffset;
    }

    public boolean containsSourceY(int sourceY) {
        return sourceY >= this.sourceMinY && sourceY < this.sourceMaxExclusiveY;
    }

    public int targetMinY() {
        return this.toTargetY(this.sourceMinY);
    }

    public int targetMaxExclusiveY() {
        return this.toTargetY(this.sourceMaxExclusiveY);
    }

    public boolean isSealY(int targetY) {
        return (this.bottomBedrock && targetY == this.targetMinY() - 1)
                || (this.topBedrock && targetY == this.targetMaxExclusiveY());
    }

    public int sealY() {
        return this.topBedrock ? this.targetMaxExclusiveY() : this.targetMinY() - 1;
    }

    public static OverworldSlice byName(String name) {
        for (OverworldSlice slice : values()) {
            if (slice.serializedName.equals(name)) {
                return slice;
            }
        }
        throw new IllegalArgumentException("unknown overworld slice: " + name);
    }
}

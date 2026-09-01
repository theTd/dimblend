package com.dimblend.worldgen;

import com.mojang.serialization.Codec;

public enum OverworldSlice {
    SURFACE("surface", 0, 32, Integer.MAX_VALUE, true),
    UNDERGROUND("underground", 96, -64, 32, false);

    public static final Codec<OverworldSlice> CODEC = Codec.STRING.xmap(OverworldSlice::byName, OverworldSlice::serializedName);

    private final String serializedName;
    private final int yOffset;
    private final int sourceMinY;
    private final int sourceMaxExclusiveY;
    private final boolean floorBedrock;

    OverworldSlice(String serializedName, int yOffset, int sourceMinY, int sourceMaxExclusiveY, boolean floorBedrock) {
        this.serializedName = serializedName;
        this.yOffset = yOffset;
        this.sourceMinY = sourceMinY;
        this.sourceMaxExclusiveY = sourceMaxExclusiveY;
        this.floorBedrock = floorBedrock;
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

    public boolean floorBedrock() {
        return this.floorBedrock;
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

    public int sealY() {
        return this.floorBedrock ? this.targetMinY() - 1 : this.targetMaxExclusiveY();
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

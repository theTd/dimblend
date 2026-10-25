package dimblend.spyglass.structure;

import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;

public final class SpyglassStructureTags {
    public static final TagKey<Structure> VISIBLE = TagKey.create(
            Registries.STRUCTURE,
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "visible"));

    private SpyglassStructureTags() {
    }
}

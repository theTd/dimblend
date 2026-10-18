package dimblend.experience.client.lighting;

import net.minecraft.core.BlockPos;
import toni.sodiumdynamiclights.DynamicLightSource;

/** Implemented only by Create Dynamic Lights' Sable sources. */
public interface SableHeadlightSource extends DynamicLightSource {
    boolean dimblend$isHeadlight();
    BlockPos dimblend$plotPosition();
    int dimblend$emission();
}

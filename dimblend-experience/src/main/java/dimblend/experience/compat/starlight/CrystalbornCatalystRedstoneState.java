package dimblend.experience.compat.starlight;

import java.util.List;

/**
 * Per-block-entity cursor storage for the adjacency-driven mining mode. The mixin implements
 * this interface on {@code CrystalbornCatalystBlockEntity}, so the rules class never keeps
 * global cursor state.
 */
public interface CrystalbornCatalystRedstoneState {

    List<CrystalbornCatalystRules.RedstoneCursor> cursors();
}

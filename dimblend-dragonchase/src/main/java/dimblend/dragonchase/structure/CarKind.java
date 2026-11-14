package dimblend.dragonchase.structure;

/** CONTRACT (Phase 0). The three Sable structures this mod builds. */
public enum CarKind {
    /** 龙巢车厢: force-loaded, no edit lock, reward slots (requirements.md §3.3, §3.6). */
    NEST,
    /** 刹车车厢: not force-loaded, bogie locked, redstone-powered brake (§3.5). */
    BRAKE,
    /** 水晶车厢: not force-loaded, bogie locked, 1-4 end crystals (§3.5). */
    CRYSTAL
}

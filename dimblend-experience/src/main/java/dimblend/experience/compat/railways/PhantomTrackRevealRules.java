package dimblend.experience.compat.railways;

/**
 * I24 追逐期间幻影轨道强制可见的纯判定。
 *
 * <p>末地走廊的轨道是 {@code railways:track_phantom_wide}（{@code CorridorTrackProfile.END}），
 * 而 Railways 只在玩家手持 {@code railways:phantom_track_revealing} 标签物品时显示幻影轨道。
 * 追逐时没人会一直举着轨道物品，整段末地轨道于是隐形。口径（用户 2026-11-18 拍板
 * {@code chase_active_only}）：只在 {@code dimblend:rotating} 维度、且追逐处于活动状态时把
 * 「手持物品」这一项视为真；追逐结束立刻回到原版规则（不拿物品就隐形）。</p>
 *
 * <p>两项合成的是上游 {@code PhantomSpriteManager#tick} 读到的那个布尔本身
 * （{@code visible = player != null && (mainMatches || offMatches)}），**不碰**上游随后的
 * {@code visible != lastVisible} 比较与 {@code lastVisible/hasChanged} 赋值。这是刻意的：
 * 若在 {@code tick} 的 RETURN 后再改 {@code visible = true; lastVisible = true}，下一 tick
 * 上游会先把它翻回 false、我们再翻回 true，{@code hasChanged} 每 tick 都为真，
 * 方块图集每秒重传 20 次贴图帧。把假值塞回上游的比较输入，翻转就仍由上游自己算：
 * 追逐开始一次、结束一次，之后静止。</p>
 */
public final class PhantomTrackRevealRules {

    /** 追逐活动且玩家在当前维度的 {@code dimblend:rotating} 时才强制可见。 */
    public static boolean revealDuringChase(boolean chaseActive, boolean inRotatingLevel) {
        return chaseActive && inRotatingLevel;
    }

    /**
     * 上游 {@code CRTags.AllItemTags.PHANTOM_TRACK_REVEALING.matches(item)} 的结果与本 mod
     * 强制可见的合成。追逐中恒为真；否则原样返回手持判定。
     */
    public static boolean isRevealed(boolean holdingMatch, boolean reveal) {
        return holdingMatch || reveal;
    }

    private PhantomTrackRevealRules() {
    }
}

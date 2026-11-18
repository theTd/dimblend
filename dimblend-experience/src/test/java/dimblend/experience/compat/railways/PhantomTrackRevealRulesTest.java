package dimblend.experience.compat.railways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * I24 幻影轨道强制可见的判定与「不每 tick 重传贴图帧」不变量。
 *
 * <p>模拟的是运行 jar 里 {@code PhantomSpriteManager.tick(Minecraft)} 的字节码形态
 * （railways-0.2.0-beta.2 的 {@code javap -p -c}）：
 * {@code visible = player != null && (mainMatches || offMatches);}
 * {@code if (visible != lastVisible || firstRun) { firstRun = false; lastVisible = visible; hasChanged = true; ... }}
 * 以及 {@code renderTick()} 的 {@code if (hasChanged) { hasChanged = false; uploadFrame(lastVisible); }}。
 * 本 mod 只把 {@code matches} 的返回值与 {@link PhantomTrackRevealRules#isRevealed} 合成，
 * 其余交给上游，所以这里的 tick 循环就是真实的组合行为。</p>
 */
class PhantomTrackRevealRulesTest {

    /** 一次 renderTick 只在上传时计数，等价于一次图集帧上传。 */
    private static final class SpriteUploads {
        private boolean lastVisible;
        private boolean firstRun = true;
        private boolean hasChanged;
        private int uploads;

        void tick(boolean holding, boolean reveal) {
            boolean visible = PhantomTrackRevealRules.isRevealed(holding, reveal);
            if (visible != lastVisible || firstRun) {
                firstRun = false;
                lastVisible = visible;
                hasChanged = true;
            }
            if (hasChanged) {
                hasChanged = false;
                uploads++;
            }
        }
    }

    @Test
    void chaseStartAndEndUploadExactlyOnceEach() {
        SpriteUploads sprites = new SpriteUploads();
        sprites.tick(false, false);
        int baseline = sprites.uploads;

        // 追逐开始：玩家不拿幻影轨道物品，可见性被强制为真
        for (int i = 0; i < 200; i++) {
            sprites.tick(false, true);
        }
        assertEquals(baseline + 1, sprites.uploads,
                "追逐开始只允许一次帧上传；之后的 tick 不得继续置 hasChanged");
        assertTrue(sprites.lastVisible);

        // 追逐结束：立刻回到原版手持规则（不拿物品 -> 隐形），同样只翻转一次
        for (int i = 0; i < 200; i++) {
            sprites.tick(false, false);
        }
        assertEquals(baseline + 2, sprites.uploads, "追逐结束只允许一次帧上传");
        assertFalse(sprites.lastVisible);

        // 反复穿越开关状态：每次切换各一次，稳态零上传
        for (int i = 0; i < 50; i++) {
            sprites.tick(false, true);
            sprites.tick(false, true);
            sprites.tick(false, false);
            sprites.tick(false, false);
        }
        assertEquals(baseline + 2 + 100, sprites.uploads);
    }

    @Test
    void alreadyVisibleTrackDoesNotReuploadWhenChaseStartsOrEnds() {
        SpriteUploads sprites = new SpriteUploads();
        for (int i = 0; i < 20; i++) {
            sprites.tick(true, false);
        }
        assertEquals(1, sprites.uploads, "手持物品显示只在第一次翻转时上传");

        for (int i = 0; i < 20; i++) {
            sprites.tick(true, true);
            sprites.tick(true, false);
        }
        assertEquals(1, sprites.uploads, "已经可见时追逐开关不应产生任何上传");
        assertTrue(sprites.lastVisible);
    }

    @Test
    void revealOnlyInRotatingLevelAndOnlyWhileChaseIsActive() {
        assertTrue(PhantomTrackRevealRules.revealDuringChase(true, true));
        assertFalse(PhantomTrackRevealRules.revealDuringChase(false, true), "追逐结束后不再强制可见");
        assertFalse(PhantomTrackRevealRules.revealDuringChase(true, false), "别的维度的幻影轨道不受影响");
        assertFalse(PhantomTrackRevealRules.revealDuringChase(false, false));
    }

    @Test
    void revealComposesWithTheHeldItemRule() {
        assertFalse(PhantomTrackRevealRules.isRevealed(false, false));
        assertTrue(PhantomTrackRevealRules.isRevealed(true, false));
        assertTrue(PhantomTrackRevealRules.isRevealed(false, true));
        assertTrue(PhantomTrackRevealRules.isRevealed(true, true));
    }
}

package dimblend.experience.compat.sable;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认关闭的 Sable 子层级动画诊断（{@code -Ddimblend.sable.animation.diag=true}）。
 *
 * <p>只回答一个具体问题：<b>子层级上的火焰/岩浆贴图是不是<b>当前图集里的那一张</b>，以及
 * Sodium 的活跃标记到底有没有落下</b>。三条判据缺一不可，任何一条被当成另一条都会得出错误结论：</p>
 * <ul>
 *   <li>{@code is_current}：用 {@code atlas.getSprite(sprite.contents().name()) == sprite} 判断
 *       缓存里的贴图实例是否仍是当前图集的那一张。{@code TextureAtlas#upload} 每次重载都整批换新实例，
 *       所以「名字对得上」不等于「实例是当前的」。<b>只有这个身份比较能判定归属</b>：贴图自带的
 *       {@code atlasLocation()} 只是资源名，图集对象的哈希也不会随之改变。</li>
 *   <li>{@code mark_call}：{@code markSpriteActive} 这次调用是否正常返回。</li>
 *   <li>{@code active}：回读 {@code SpriteContentsExtension#sodium$isActive()} 的真实值。
 *       {@code mark_call=true} 只说明反射没抛异常，<b>不等于</b>贴图真的被标成活跃——后者才是
 *       Sodium 推进动画的依据。回读不可用时记 {@code n/a}，不写成 false。</li>
 * </ul>
 *
 * <p>采样是<b>全局 2 秒一次</b>（不分区块、不按刻配额）：一次只记一行，看的就是当前正在标记的
 * 那张贴图，足够判断题，又不会刷屏。状态级采样另有 {@link #MAX_STATE_SAMPLES} 上限。
 * 默认关闭时所有方法空转，不留开销；本类不改变任何 GL / 渲染状态。</p>
 */
public final class SableAnimationDiagnostics {

    /** 采样总开关，默认关闭。 */
    static final boolean ENABLED = Boolean.getBoolean("dimblend.sable.animation.diag");
    /** 粒子位姿校正采样开关，默认关闭；与贴图采样互不影响。 */
    static final boolean PARTICLE_ENABLED = Boolean.getBoolean("dimblend.sable.particle.diag");
    private static final Logger LOGGER = LoggerFactory.getLogger("dimblend_experience/sable-animation-diag");
    /** 采样最短间隔（毫秒）：全局共享，2 秒最多一行。 */
    private static final long SECTION_SAMPLE_INTERVAL_MS = 2_000L;
    /** 状态级采样的上限：只够看清「哪些方块状态算出了动画贴图」，不刷屏。 */
    private static final int MAX_STATE_SAMPLES = 24;

    private static long lastSectionLogAt;
    private static long lastParticleLogAt;
    private static int sectionsLogged;
    private static int statesLogged;

    /**
     * 记录一次「粒子按渲染位姿平移」采样：与区块采样同一 2 秒全局限频，默认关闭时直接返回。
     *
     * @param corrected 本帧被平移的粒子数
     * @param maxShift  本帧最大位移（格）——移动载具上的偏差就是这个量级
     */
    public static void particleShift(int corrected, double maxShift) {
        if (!PARTICLE_ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastParticleLogAt < SECTION_SAMPLE_INTERVAL_MS) {
            return;
        }
        lastParticleLogAt = now;
        LOGGER.info("[sable-particle] 按渲染位姿平移 {} 颗粒子，最大位移 {} 格",
                corrected, String.format(Locale.ROOT, "%.4f", maxShift));
    }

    static void generation(long epoch) {
        if (!ENABLED) {
            return;
        }
        LOGGER.info("[sable-anim] 贴图代次推进 epoch={}", epoch);
    }

    /**
     * 记录一次标记采样。
     *
     * @param markedCall {@code markSpriteActive} 是否正常返回（反射成功，不等于动画活跃）
     */
    static void markedSample(BlockPos origin, SectionRenderDispatcher.CompiledSection compiled,
            long stateEpoch, long sectionEpoch, TextureAtlasSprite sprite, boolean markedCall) {
        if (!ENABLED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSectionLogAt < SECTION_SAMPLE_INTERVAL_MS) {
            return;
        }
        lastSectionLogAt = now;
        sectionsLogged++;

        TextureAtlasSprite current = resolveCurrent(sprite);
        Boolean active = SodiumSpriteAnimationBridge.readActive(sprite);
        LOGGER.info("[sable-anim] section origin={} compiled={} state_epoch={} section_epoch={}"
                        + " sprite={} sprite_at={} current_at={} is_current={} mark_call={} active={} samples={}",
                origin, identity(compiled), stateEpoch, sectionEpoch,
                sprite.contents().name(), identity(sprite), identity(current),
                sprite == current, markedCall, active == null ? "n/a" : active, sectionsLogged);
    }

    static void computedSample(BlockState state, int spriteCount) {
        if (!ENABLED || statesLogged >= MAX_STATE_SAMPLES) {
            return;
        }
        statesLogged++;
        LOGGER.info("[sable-anim] computed state={} animated_sprites={}", state, spriteCount);
    }

    /**
     * 当前图集里同一名字的贴图实例。
     *
     * <p>按贴图自己声明的 {@code atlasLocation()} 去查那张图集（贴图可能来自方块图集或粒子图集），
     * 再按 {@code SpriteContents#name()} 取实例。查不到或图集未初始化时返回 {@code null}，
     * 此时 {@code is_current=false} 表示「无法证实是当前实例」，不等于「确定过期」。</p>
     */
    private static TextureAtlasSprite resolveCurrent(TextureAtlasSprite sprite) {
        try {
            var lookup = Minecraft.getInstance().getTextureAtlas(sprite.atlasLocation());
            return lookup == null ? null : lookup.apply(sprite.contents().name());
        } catch (RuntimeException failure) {
            // 图集尚未初始化/正在重建：这一刻没有答案。
            return null;
        }
    }

    /** 只给身份哈希：{@code CompiledSection} 是匿名类，{@code getSimpleName()} 恒为空。 */
    private static String identity(Object value) {
        return value == null ? "null" : "@" + Integer.toHexString(System.identityHashCode(value));
    }

    /** 诊断模式下拼一行「当前贴图代次」，供外部（命令/日志）读取；关闭时为固定串。 */
    public static String describe() {
        if (!ENABLED) {
            return "diag=off";
        }
        Object atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        return String.format(Locale.ROOT, "epoch=%d block_atlas=%s", SubLevelAnimatedSprites.stateEpoch(),
                identity(atlas));
    }

    private SableAnimationDiagnostics() {
    }
}

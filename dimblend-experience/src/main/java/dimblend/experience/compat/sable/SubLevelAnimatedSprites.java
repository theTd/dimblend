package dimblend.experience.compat.sable;

import java.util.Collection;
import java.util.List;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 为 Sable 子层级（载具）的 vanilla 区块网格补上 Sodium 的「动画贴图活跃标记」。
 *
 * <p>Sable 的 {@code SODIUM_REACHAROUND} 渲染器不走 Sodium 的区块网格，而是用自己的
 * {@code SectionRenderDispatcher} 构建 vanilla 网格。Sodium 开了
 * {@code performance.animate_only_visible_textures} 后，只推进它自己可见区块网格里出现过的
 * 动画贴图（{@code RenderSectionManager#tickVisibleRenders}）；子层级网格里的贴图没人标记，
 * 于是火、岩浆、海晶灯等贴图在载具上冻住，正常地形里却正常。</p>
 *
 * <p>这里按「已编译区块」缓存每个子层级区块用到的动画贴图，每个游戏刻逐张贴图打一次活跃
 * 标记（Sodium 的贴图 tick 每刻清空一次）：区块重建时才扫 16³ 方块（BlockState 级结果全局
 * 缓存），平时只有数组遍历 + 标记，不改变 Sodium 对其余世界的优化。</p>
 *
 * <h2>为什么必须有「代次」</h2>
 * <p>{@code TextureAtlas#upload} 每次资源重载都会把 {@code texturesByName} 整体换成新的
 * {@code TextureAtlasSprite} 实例（旧实例连同 {@code SpriteContents}/{@code Ticker} 一起 close，
 * 见 vanilla {@code TextureAtlas#upload} 与 {@code #clearTextureData}）。而 {@code TextureAtlas}
 * 对象本身在 {@code ModelManager} 里是常驻的，重载后身份不变——所以不能用图集身份判断代次。
 * 可靠的代次信号是<b>贴图实例身份</b>：取一张固定探针贴图（岩浆流动帧）在当前图集里的实例，
 * 一旦身份变化就作废全部贴图缓存。这样既不依赖 reload listener 的执行顺序，也不需要额外 mixin
 * 注册，并且每刻只有一次哈希查表。</p>
 *
 * <p>失败的贴图读取（模型/图集尚未就绪、桥调用抛错）不会永久缓存成空集合：{@link
 * SableAnimationSpriteCache} 只记一个短重试窗口，到期自动重算。</p>
 */
public final class SubLevelAnimatedSprites {

    private static final TextureAtlasSprite[] NO_SPRITES = new TextureAtlasSprite[0];
    private static final RandomSource RANDOM = RandomSource.create();

    /** BlockState 实例是驻留的，按引用缓存每个状态用到的动画贴图。 */
    private static final SableAnimationSpriteCache<BlockState, TextureAtlasSprite[]> STATE_SPRITES =
            new SableAnimationSpriteCache<>(NO_SPRITES, 40L, 20L);

    private final SableAnimationSpriteCache<SectionRenderDispatcher.RenderSection, TextureAtlasSprite[]> sections =
            new SableAnimationSpriteCache<>(NO_SPRITES);
    private long lastMarkedTick = Long.MIN_VALUE;
    /**
     * 探针贴图与代次是全局的：贴图缓存被所有子层级共享，重载判断也必须只有一份。
     */
    private static TextureAtlasSprite PROBE_SPRITE;
    /** 资源重载代数：每个子层级据此作废自己的区块级缓存（区块实例身份本身不随之改变）。 */
    private static long RELOAD_GENERATION;
    private long seenGeneration = -1L;

    /** Sodium 的动画贴图 API 在场时才需要补标记（原版渲染器本就不会冻结动画）。 */
    public static boolean isSupported() {
        return SodiumSpriteAnimationBridge.isAvailable();
    }

    /** 当前贴图代次（每检测到一次资源重载 +1）；诊断与单测可读。 */
    public static long stateEpoch() {
        return STATE_SPRITES.epoch();
    }

    /** 子层级渲染前调用：为本帧要绘制的区块补打动画贴图活跃标记。 */
    public void markSections(Collection<SectionRenderDispatcher.RenderSection> renderSections, Level level) {
        if (!SodiumSpriteAnimationBridge.isAvailable()) {
            return;
        }
        // Sodium 的贴图 tick 每个游戏刻跑一次并在末尾清空活跃标记，因此每刻补打一次即可覆盖。
        long gameTime = level.getGameTime();
        if (gameTime == this.lastMarkedTick) {
            return;
        }
        this.lastMarkedTick = gameTime;
        STATE_SPRITES.advance(gameTime);
        this.sections.advance(gameTime);
        this.refreshGeneration();

        // 已释放的渲染区块会换实例，旧条目没有复用的可能。只在「缓存条目比在用区块多」时清理，
        // 常态下不建临时表。
        if (this.sections.cachedCount() > renderSections.size()) {
            this.sections.retain(renderSections);
        }

        for (SectionRenderDispatcher.RenderSection section : renderSections) {
            SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
            if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED
                    || compiled == SectionRenderDispatcher.CompiledSection.EMPTY) {
                continue;
            }
            // 用 getPartial：区块集合只要含「还没恢复」的状态，就只缓存一个重试窗口，
            // 否则失败状态在别的状态成功后永远不会再被重算（见 collect 的完整标志）。
            TextureAtlasSprite[] sprites = this.sections.getPartial(section, compiled,
                    key -> collect(level, key.getOrigin()));
            for (TextureAtlasSprite sprite : sprites) {
                boolean marked = SodiumSpriteAnimationBridge.markActive(sprite);
                // 采样本身有 2 秒全局限频，这里不用再限额；只避免关诊断时进方法。
                if (SableAnimationDiagnostics.ENABLED) {
                    SableAnimationDiagnostics.markedSample(section.getOrigin(), compiled,
                            STATE_SPRITES.epoch(), this.sections.epoch(), sprite, marked);
                }
            }
        }
    }

    /**
     * 检查资源重载代次。
     *
     * <p>取一张固定探针贴图（岩浆流动帧）在当前图集里的实例：{@code TextureAtlas#upload} 每次重载
     * 都整批换新的 {@code TextureAtlasSprite} 实例，因此探针实例身份变化就等价于「重载已应用」。
     * 这一步只做一次哈希查表，不做模型查询、不遍历方块。</p>
     *
     * <p>不能改判「探针方块模型实例」：{@code BlockModelShaper#getBlockModel} 对找不到的状态会
     * 返回常驻的 missing model，探针方块一旦缺模型身份就恒不变，重载会被漏判。</p>
     */
    private void refreshGeneration() {
        TextureAtlasSprite probe = flowProbe();
        // 读不到（图集正在重建/未初始化）时不动代次：那是「这一刻没有答案」，不是「没变」。
        // 读到时只要实例变了就作废——首次赋值也作废，此时缓存本应为空，代价为零。
        if (probe != null && probe != PROBE_SPRITE) {
            PROBE_SPRITE = probe;
            // 图集已换过一批贴图：所有按状态缓存的贴图数组都必须作废。
            STATE_SPRITES.invalidate();
            RELOAD_GENERATION++;
            SableAnimationDiagnostics.generation(STATE_SPRITES.epoch());
        }
        if (this.seenGeneration != RELOAD_GENERATION) {
            this.seenGeneration = RELOAD_GENERATION;
            // 区块实例在重载后仍是同一批，compiled 也未必换实例：区块级缓存必须显式作废。
            this.sections.invalidate();
        }
    }

    /** 当前图集里的岩浆流动贴图实例；图集尚未初始化时返回 null（此时也不该缓存任何贴图）。 */
    private static TextureAtlasSprite flowProbe() {
        try {
            return ModelBakery.LAVA_FLOW.sprite();
        } catch (RuntimeException failure) {
            // 图集未初始化时 getSprite 会抛 IllegalStateException；当作「本刻读不到」。
            return null;
        }
    }

    /**
     * 扫一个区块，汇总它用到的动画贴图。
     *
     * <p>结果的<b>完整性</b>必须被传播出去：区块里有 64 个状态很常见，只要其中一个状态此刻
     * 「模型/图集还没就绪」，它这一格就暂时没有贴图。若把这种混合结果当成完整的永久缓存，那个
     * 失败状态再也没有机会被 {@code get} 到，它自己的重试窗口等于从未开始——区块就这样永久缺一格
     * 动画。所以这里任何一个状态不完整（或仍在等待重试），整个区块结果都标成不完整。</p>
     */
    private static SableAnimationSpriteCache.Computed<TextureAtlasSprite[]> collect(Level level, BlockPos origin) {
        ReferenceOpenHashSet<TextureAtlasSprite> sprites = new ReferenceOpenHashSet<>();
        boolean complete = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    for (TextureAtlasSprite sprite : spritesFor(state)) {
                        sprites.add(sprite);
                    }
                    if (STATE_SPRITES.isPending(state)) {
                        complete = false;
                    }
                }
            }
        }
        TextureAtlasSprite[] value = sprites.isEmpty() ? NO_SPRITES : sprites.toArray(NO_SPRITES);
        return complete
                ? SableAnimationSpriteCache.Computed.complete(value)
                : SableAnimationSpriteCache.Computed.partial(value);
    }

    private static TextureAtlasSprite[] spritesFor(BlockState state) {
        return STATE_SPRITES.getPartial(state, state, SubLevelAnimatedSprites::compute);
    }

    private static SableAnimationSpriteCache.Computed<TextureAtlasSprite[]> compute(BlockState state) {
        ReferenceOpenHashSet<TextureAtlasSprite> sprites = new ReferenceOpenHashSet<>();
        if (state.getRenderShape() == RenderShape.MODEL) {
            BakedModel model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
            addQuads(sprites, model.getQuads(state, null, RANDOM));
            for (Direction direction : Direction.values()) {
                addQuads(sprites, model.getQuads(state, direction, RANDOM));
            }
        }
        // 流体方块没有 MODEL 网格，贴图由 LiquidBlockRenderer 直接取；只处理原版水/岩浆，
        // 其余模组流体若有方块模型，上面的 MODEL 分支已覆盖。
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.is(Fluids.WATER)) {
                addIfAnimated(sprites, ModelBakery.WATER_FLOW.sprite());
            } else if (fluid.is(Fluids.LAVA)) {
                addIfAnimated(sprites, ModelBakery.LAVA_FLOW.sprite());
            }
            // 与 LiquidBlockRenderer.setupSprites 同源：静止水面/岩浆面取流体方块模型的粒子图标。
            addIfAnimated(sprites, Minecraft.getInstance().getModelManager().getBlockModelShaper()
                    .getBlockModel(state).getParticleIcon());
        }
        if (SableAnimationDiagnostics.ENABLED && !sprites.isEmpty()) {
            SableAnimationDiagnostics.computedSample(state, sprites.size());
        }
        // 「这个状态确实没有动画贴图」是完整答案（普通石头不该每 40 tick 重扫）；
        // 读取失败是另一条路径（返回 null），由缓存按短窗口重试。
        return SableAnimationSpriteCache.Computed.complete(
                sprites.isEmpty() ? NO_SPRITES : sprites.toArray(NO_SPRITES));
    }

    private static void addQuads(ReferenceOpenHashSet<TextureAtlasSprite> sprites, List<BakedQuad> quads) {
        if (quads == null) {
            return;
        }
        for (BakedQuad quad : quads) {
            addIfAnimated(sprites, quad.getSprite());
        }
    }

    private static void addIfAnimated(ReferenceOpenHashSet<TextureAtlasSprite> sprites, TextureAtlasSprite sprite) {
        if (sprite != null && SodiumSpriteAnimationBridge.hasAnimation(sprite)) {
            sprites.add(sprite);
        }
    }
}

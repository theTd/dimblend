package dimblend.experience.compat.sable;

import java.util.Collection;
import java.util.List;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelDataManager;

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
 * <h2>动画贴图从哪里来（四条路径，缺一条就有一类方块冻住）</h2>
 * <ol>
 *   <li><b>普通方块模型</b>：{@code model.getQuads(state, dir, RANDOM)} 的贴图。</li>
 *   <li><b>流体</b>：{@code Fluids.WATER}/{@code Fluids.LAVA} 这类「源」判定只覆盖
 *       {@code WaterFluid.Source}，{@code WaterFluid.Flowing}（{@code LEVEL != 0}）根本匹配不上，
 *       于是流动的水/岩浆在载具上冻住。改用 {@code FluidTags.WATER}/{@code FluidTags.LAVA}
 *       （与 {@code ModelBakery.WATER_FLOW/LAVA_FLOW} 同一张贴图），并且额外问
 *       {@link IClientFluidTypeExtensions} 要 still/flowing 两张贴图，模组流体（不同注册名、
 *       自定义材质）因此也能被标记。</li>
 *   <li><b>{@code ModelData} 方块实体</b>（Copycats+ 的伪装材质、Create 的多状态方块）：伪装材质
 *       存在 {@code BlockEntity#getModelData()} 里，3 参数的
 *       {@code model.getQuads(state, dir, RANDOM)} 等价于 {@code ModelData.EMPTY}，材质 quad
 *       一条都拿不到。所以对 {@code state.hasBlockEntity()} 的方块单独用 NeoForge 的 5 参数
 *       {@code getQuads(..., modelData, renderType)} 扫一遍。<b>这一路不进 {@code STATE_SPRITES}</b>：
 *       那个缓存按 {@code BlockState} 存的是 {@code ModelData.EMPTY} 的结果，混进材质贴图会让
 *       别的同类方块（没有该 ModelData）也标错贴图。</li>
 *   <li><b>单方块子层级</b>（{@code VanillaSingleSubLevelRenderData}）：它没有区块网格，只有
 *       一个方块位置，见 {@link #markSingleBlock}。</li>
 * </ol>
 *
 * <h2>为什么必须有「代次」</h2>
 * <p>{@code TextureAtlas#upload} 每次资源重载都会把 {@code texturesByName} 整体换成新的
 * {@code TextureAtlasSprite} 实例（旧实例连同 {@code SpriteContents}/{@code Ticker} 一起 close，
 * 见 vanilla {@code TextureAtlas#upload} 与 {@code #clearTextureData}）。而 {@code TextureAtlas}
 * 对象本身在 {@code ModelManager} 里是常驻的，重载后身份不变——所以不能用图集身份判断代次。
 * 可靠的代次信号是<b>贴图实例身份</b>：取两张固定探针贴图（岩浆流动帧、方块图集的
 * {@code missingno}）在当前图集里的实例，任一身份变化就作废全部贴图缓存。两个探针互为兜底：
 * 某个版本里 {@code ModelBakery.LAVA_FLOW} 读不到/没被重载替换时，{@code missingno} 仍会随
 * {@code #upload} 换新实例，任何一次图集重传都不会漏判。这样既不依赖 reload listener 的执行
 * 顺序，也不需要额外 mixin 注册，并且每刻只有两次哈希查表。</p>
 *
 * <p>失败的贴图读取（模型/图集尚未就绪、桥调用抛错）不会永久缓存成空集合：{@link
 * SableAnimationSpriteCache} 只记一个短重试窗口，到期自动重算。</p>
 */
public final class SubLevelAnimatedSprites {

    private static final TextureAtlasSprite[] NO_SPRITES = new TextureAtlasSprite[0];
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Direction[] DIRECTIONS = Direction.values();

    /** BlockState 实例是驻留的，按引用缓存每个状态用到的动画贴图（只含 {@code ModelData.EMPTY} 的结果）。 */
    private static final SableAnimationSpriteCache<BlockState, TextureAtlasSprite[]> STATE_SPRITES =
            new SableAnimationSpriteCache<>(NO_SPRITES, 40L, 20L);

    private final SableAnimationSpriteCache<SectionRenderDispatcher.RenderSection, TextureAtlasSprite[]> sections =
            new SableAnimationSpriteCache<>(NO_SPRITES);
    /**
     * 单方块子层级的贴图缓存：键是方块位置（不可变实例，按引用比较），stamp 是 {@code BlockState}
     * 实例（方块状态本身就是驻留的）。资源重载时随代次整体作废（见 {@link #refreshGeneration()}）。
     */
    private final SableAnimationSpriteCache<BlockPos, TextureAtlasSprite[]> singleBlock =
            new SableAnimationSpriteCache<>(NO_SPRITES);

    private long lastMarkedTick = Long.MIN_VALUE;
    private long lastSingleBlockTick = Long.MIN_VALUE;
    private BlockPos singleBlockPos;
    private BlockState singleBlockState;
    private ModelData singleBlockModelData = ModelData.EMPTY;
    private Object singleBlockStamp = new Object();

    /**
     * 探针贴图与代次是全局的：贴图缓存被所有子层级共享，重载判断也必须只有一份。
     */
    private static TextureAtlasSprite FLOW_PROBE;
    private static TextureAtlasSprite MISSING_PROBE;
    /** 资源重载代数：每个子层级据此作废自己的区块级/单方块缓存（区块实例身份本身不随之改变）。 */
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
     * 单方块子层级渲染前调用：给那一个方块用到的动画贴图补打活跃标记。
     *
     * <p>单方块渲染数据（{@code VanillaSingleSubLevelRenderData}）没有区块网格，
     * {@link #markSections} 永远不会被它调用，因此它上面的火/岩浆/伪装材质需要这条独立入口。
     * 支持普通方块、流动流体与 {@code ModelData} 方块实体（都经 {@link #computeSingleBlock}）。</p>
     *
     * <p>限频与失效：同一游戏刻、同一位置、同一状态时直接返回；位置或状态一变立即重算
     * （缓存键是位置、stamp 是状态实例，二者任一变化都会重扫）；资源重载由
     * {@link #refreshGeneration()} 推进代次并作废本条缓存。</p>
     *
     * @param level 子层级所在的客户端世界
     * @param pos   单方块的位置（plot 坐标）
     * @param state 该位置的方块状态
     */
    public void markSingleBlock(Level level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null || !SodiumSpriteAnimationBridge.isAvailable()) {
            return;
        }
        ModelData currentModelData = ModelData.EMPTY;
        if (state.hasBlockEntity()) {
            try {
                ModelData fetched = modelDataAt(level, pos);
                if (fetched != null) {
                    currentModelData = fetched;
                }
            } catch (RuntimeException | LinkageError ignored) {
                currentModelData = null;
            }
        }
        long gameTime = level.getGameTime();
        if (gameTime == this.lastSingleBlockTick
                && pos.equals(this.singleBlockPos)
                && state == this.singleBlockState
                && currentModelData == this.singleBlockModelData) {
            return;
        }
        this.lastSingleBlockTick = gameTime;
        if (!pos.equals(this.singleBlockPos)) {
            // 缓存键按引用比较：位置一变就换成自己的不可变副本，并把旧位置条目清掉
            // （单方块渲染数据只有一个方块，缓存里最多留一条）。
            this.singleBlockPos = new BlockPos(pos);
            this.singleBlock.retain(List.of(this.singleBlockPos));
        }
        if (state != this.singleBlockState || currentModelData != this.singleBlockModelData) {
            this.singleBlockState = state;
            this.singleBlockModelData = currentModelData;
            this.singleBlockStamp = new Object();
        }

        STATE_SPRITES.advance(gameTime);
        this.singleBlock.advance(gameTime);
        this.refreshGeneration();

        TextureAtlasSprite[] sprites = this.singleBlock.getPartial(this.singleBlockPos, this.singleBlockStamp,
                key -> computeSingleBlock(level, key, state));
        for (TextureAtlasSprite sprite : sprites) {
            SodiumSpriteAnimationBridge.markActive(sprite);
        }
    }

    /**
     * 检查资源重载代次。
     *
     * <p>取两张固定探针贴图在当前图集里的实例：{@code TextureAtlas#upload} 每次重载都整批换新的
     * {@code TextureAtlasSprite} 实例，因此<b>任一</b>探针实例身份变化就等价于「重载已应用」。
     * 一步只有两次哈希查表，不做模型查询、不遍历方块。</p>
     *
     * <p>两个探针的必要性：{@code ModelBakery.LAVA_FLOW} 是岩浆流动帧，若该版本/资源包里它读不到
     * 或者没被重载替换，单探针就会漏判；方块图集的 {@code minecraft:missingno} 由
     * {@code TextureAtlas#upload} 每次重建（vanilla {@code TextureAtlas.java:61}），任何一次图集
     * 重传都会换新实例，所以它兜住另一半。</p>
     *
     * <p>不能改判「探针方块模型实例」：{@code BlockModelShaper#getBlockModel} 对找不到的状态会
     * 返回常驻的 missing model，探针方块一旦缺模型身份就恒不变，重载会被漏判。</p>
     */
    private void refreshGeneration() {
        boolean reloaded = false;
        TextureAtlasSprite flow = flowProbe();
        // 读不到（图集正在重建/未初始化）时不动代次：那是「这一刻没有答案」，不是「没变」。
        // 读到时只要实例变了就作废——首次赋值也作废，此时缓存本应为空，代价为零。
        if (flow != null && flow != FLOW_PROBE) {
            FLOW_PROBE = flow;
            reloaded = true;
        }
        TextureAtlasSprite missing = missingProbe();
        if (missing != null && missing != MISSING_PROBE) {
            MISSING_PROBE = missing;
            reloaded = true;
        }
        if (reloaded) {
            // 图集已换过一批贴图：所有按状态缓存的贴图数组都必须作废。
            STATE_SPRITES.invalidate();
            RELOAD_GENERATION++;
            SableAnimationDiagnostics.generation(STATE_SPRITES.epoch());
        }
        if (this.seenGeneration != RELOAD_GENERATION) {
            this.seenGeneration = RELOAD_GENERATION;
            // 区块实例/方块位置在重载后仍是同一批，compiled 也未必换实例：这两级缓存必须显式作废。
            this.sections.invalidate();
            this.singleBlock.invalidate();
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

    /** 当前方块图集里的 {@code missingno} 实例；图集/贴图尚未就绪时返回 null。 */
    private static TextureAtlasSprite missingProbe() {
        try {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            return atlas == null ? null : atlas.getSprite(MissingTextureAtlasSprite.getLocation());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * 扫一个区块，汇总它用到的动画贴图。
     *
     * <p>结果的<b>完整性</b>必须被传播出去：区块里有 64 个状态很常见，只要其中一个状态此刻
     * 「模型/图集还没就绪」（或它的 {@code ModelData} 读失败），它这一格就暂时没有贴图。若把这种
     * 混合结果当成完整的永久缓存，那个失败状态再也没有机会被 {@code get} 到，它自己的重试窗口
     * 等于从未开始——区块就这样永久缺一格动画。所以这里任何一个状态不完整（或仍在等待重试），
     * 整个区块结果都标成不完整。</p>
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
                    if (state.hasBlockEntity() && !addModelDataSprites(sprites, level, cursor, state)) {
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

    /**
     * 单方块子层级的那一个方块用了哪些动画贴图。
     *
     * <p>与 {@link #collect} 同一口径：普通方块/流体走 {@code STATE_SPRITES}，方块实体额外补
     * {@code ModelData} 的 quad 贴图；任一读取失败就把结果标成残缺（短窗口重试）。
     * 方块状态由调用方传入（与缓存的 stamp 是同一个实例），不在这里重读世界，避免
     * 「用新世界的状态算出来的贴图挂在旧 stamp 下」。</p>
     */
    private static SableAnimationSpriteCache.Computed<TextureAtlasSprite[]> computeSingleBlock(Level level,
            BlockPos pos, BlockState state) {
        ReferenceOpenHashSet<TextureAtlasSprite> sprites = new ReferenceOpenHashSet<>();
        for (TextureAtlasSprite sprite : spritesFor(state)) {
            sprites.add(sprite);
        }
        boolean complete = !STATE_SPRITES.isPending(state);
        if (state.hasBlockEntity() && !addModelDataSprites(sprites, level, pos, state)) {
            complete = false;
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
            for (Direction direction : DIRECTIONS) {
                addQuads(sprites, model.getQuads(state, direction, RANDOM));
            }
        }
        // 流体方块没有 MODEL 网格，贴图由 LiquidBlockRenderer 直接取。这里按「流体标签 + 客户端
        // 流体扩展」两条来源取：标签覆盖原版水的 Source/Flowing 两种状态（只看
        // Fluids.WATER 会漏掉 LEVEL != 0 的流动水），扩展覆盖注册名/材质不同的模组流体。
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            if (fluid.is(FluidTags.WATER)) {
                addIfAnimated(sprites, ModelBakery.WATER_FLOW.sprite());
            } else if (fluid.is(FluidTags.LAVA)) {
                addIfAnimated(sprites, ModelBakery.LAVA_FLOW.sprite());
            }
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
            addAtlasSprite(sprites, extensions.getStillTexture());
            addAtlasSprite(sprites, extensions.getFlowingTexture());
            // 与 LiquidBlockRenderer.setupSprites 同源：静止水面/岩浆面取流体方块模型的粒子图标。
            addIfAnimated(sprites, Minecraft.getInstance().getModelManager().getBlockModelShaper()
                    .getBlockModel(state).getParticleIcon());
        }
        if (SableAnimationDiagnostics.ENABLED && !sprites.isEmpty()) {
            SableAnimationDiagnostics.computedSample(state, sprites.size());
        }
        // 「这个状态确实没有动画贴图」是完整答案（普通石头不该每 40 tick 重扫）；
        // 读取失败是另一条路径（返回 null 或抛异常），由缓存按短窗口重试。
        return SableAnimationSpriteCache.Computed.complete(
                sprites.isEmpty() ? NO_SPRITES : sprites.toArray(NO_SPRITES));
    }

    /**
     * 把该方块实体 {@code ModelData} 下的材质 quad 贴图加进集合。
     *
     * <p>Copycats+ 把伪装材质存进 {@code BlockEntity#getModelData()}，3 参数的
     * {@code getQuads(state, dir, RANDOM)} 等价于 {@code ModelData.EMPTY}，材质 quad 为 0 条；
     * 必须走 NeoForge 的 5 参数重载，并按 {@code getRenderTypes(state, RANDOM, modelData)} 给出的
     * 每个 {@code RenderType} 再各取一遍（模型可以按 RenderType 返回不同 quad）。</p>
     *
     * <p>结果直接并入调用方的集合，<b>不进 {@code STATE_SPRITES}</b>——那个缓存按
     * {@code BlockState} 存 {@code ModelData.EMPTY} 的结果，混进材质贴图会污染同状态的其它方块。</p>
     *
     * @return 读取成功为 true；{@code ModelData} 读取失败为 false（调用方据此把结果标成残缺）
     */
    private static boolean addModelDataSprites(ReferenceOpenHashSet<TextureAtlasSprite> sprites, Level level,
            BlockPos pos, BlockState state) {
        ModelData modelData;
        try {
            modelData = modelDataAt(level, pos);
        } catch (RuntimeException | LinkageError failure) {
            // 模型数据/方块实体此刻读不到：本刻没有答案，让上层按短窗口重试。
            return false;
        }
        if (modelData == null || modelData == ModelData.EMPTY) {
            return true;
        }

        BakedModel model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);
        addQuads(sprites, model.getQuads(state, null, RANDOM, modelData, null));
        for (Direction direction : DIRECTIONS) {
            addQuads(sprites, model.getQuads(state, direction, RANDOM, modelData, null));
        }
        for (RenderType renderType : model.getRenderTypes(state, RANDOM, modelData)) {
            addQuads(sprites, model.getQuads(state, null, RANDOM, modelData, renderType));
            for (Direction direction : DIRECTIONS) {
                addQuads(sprites, model.getQuads(state, direction, RANDOM, modelData, renderType));
            }
        }
        return true;
    }

    /**
     * 读该位置的 {@code ModelData}：优先用 {@code ModelDataManager} 的缓存（与区块网格同源），
     * 拿不到（管理器为 null、缓存为空）再退回方块实体自己给出的那份。
     */
    private static ModelData modelDataAt(Level level, BlockPos pos) {
        ModelDataManager manager = level.getModelDataManager();
        if (manager != null && level.isClientSide) {
            ModelData cached = manager.getAt(pos);
            if (cached != ModelData.EMPTY) {
                return cached;
            }
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? ModelData.EMPTY : blockEntity.getModelData();
    }

    private static void addAtlasSprite(ReferenceOpenHashSet<TextureAtlasSprite> sprites, ResourceLocation location) {
        if (location == null) {
            return;
        }
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        if (atlas == null) {
            return;
        }
        addIfAnimated(sprites, atlas.getSprite(location));
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

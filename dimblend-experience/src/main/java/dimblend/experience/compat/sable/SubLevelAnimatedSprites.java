package dimblend.experience.compat.sable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
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
 */
public final class SubLevelAnimatedSprites {

    private static final TextureAtlasSprite[] NO_SPRITES = new TextureAtlasSprite[0];
    /** BlockState 实例是驻留的，按引用缓存每个状态用到的动画贴图。 */
    private static final Map<BlockState, TextureAtlasSprite[]> STATE_SPRITES = new Reference2ObjectOpenHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private final Map<SectionRenderDispatcher.RenderSection, CachedSection> sections = new IdentityHashMap<>();
    private long lastMarkedTick = Long.MIN_VALUE;

    /** Sodium 的动画贴图 API 在场时才需要补标记（原版渲染器本就不会冻结动画）。 */
    public static boolean isSupported() {
        return SodiumSpriteAnimationBridge.isAvailable();
    }

    /** 子层级渲染前调用：为本帧要绘制的区块补打动画贴图活跃标记。 */
    public void markSections(Iterable<SectionRenderDispatcher.RenderSection> renderSections, Level level) {
        if (!SodiumSpriteAnimationBridge.isAvailable()) {
            return;
        }
        // Sodium 的贴图 tick 每个游戏刻跑一次并在末尾清空活跃标记，因此每刻补打一次即可覆盖。
        long gameTime = level.getGameTime();
        if (gameTime == this.lastMarkedTick) {
            return;
        }
        this.lastMarkedTick = gameTime;
        for (SectionRenderDispatcher.RenderSection section : renderSections) {
            SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
            if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED
                    || compiled == SectionRenderDispatcher.CompiledSection.EMPTY) {
                continue;
            }
            CachedSection cached = this.sections.get(section);
            if (cached == null || cached.compiled() != compiled) {
                cached = new CachedSection(compiled, collect(level, section.getOrigin()));
                this.sections.put(section, cached);
            }
            for (TextureAtlasSprite sprite : cached.sprites()) {
                SodiumSpriteAnimationBridge.markActive(sprite);
            }
        }
    }

    private static TextureAtlasSprite[] collect(Level level, BlockPos origin) {
        ReferenceOpenHashSet<TextureAtlasSprite> sprites = new ReferenceOpenHashSet<>();
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
                }
            }
        }
        return sprites.isEmpty() ? NO_SPRITES : sprites.toArray(NO_SPRITES);
    }

    private static TextureAtlasSprite[] spritesFor(BlockState state) {
        TextureAtlasSprite[] cached = STATE_SPRITES.get(state);
        if (cached != null) {
            return cached;
        }
        TextureAtlasSprite[] computed = compute(state);
        STATE_SPRITES.put(state, computed);
        return computed;
    }

    private static TextureAtlasSprite[] compute(BlockState state) {
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
        return sprites.isEmpty() ? NO_SPRITES : sprites.toArray(NO_SPRITES);
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

    private record CachedSection(Object compiled, TextureAtlasSprite[] sprites) {
    }
}

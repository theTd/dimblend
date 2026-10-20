package dimblend.experience.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dimblend.experience.compat.sable.SubLevelAnimatedSprites;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 恢复 Sable 子层级上的贴图动画：子层级的 vanilla 区块网格不经过 Sodium 的网格/可见区块
 * 统计，Sodium 的 {@code animate_only_visible_textures} 永远不会把它们的动画贴图标成活跃，
 * 于是载具上的火、岩浆、海晶灯等贴图冻住。
 *
 * <p>在 {@code renderChunkedSubLevel}（每个 RenderType 一次）前，把本帧要绘制的子层级区块
 * 用到的动画贴图逐张标活跃；贴图集合按已编译区块缓存，区块重建时才扫描，且每个游戏刻只补
 * 打一次（Sodium 的贴图 tick 每刻清空一次活跃标记）。</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData", remap = false)
public abstract class SableSubLevelSpriteAnimationMixin {

    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Shadow
    @Final
    private ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections;

    @Unique
    private SubLevelAnimatedSprites dimblend$animatedSprites;

    @Inject(method = "renderChunkedSubLevel", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$markSubLevelAnimatedSprites(RenderType layer, ShaderInstance shader, Matrix4f modelView,
            double camX, double camY, double camZ, CallbackInfo ci) {
        if (!SubLevelAnimatedSprites.isSupported()) {
            return;
        }
        if (this.dimblend$animatedSprites == null) {
            this.dimblend$animatedSprites = new SubLevelAnimatedSprites();
        }
        this.dimblend$animatedSprites.markSections(this.allRenderSections, this.subLevel.getLevel());
    }
}

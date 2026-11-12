package dimblend.experience.mixin.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dimblend.experience.compat.sable.SubLevelAnimatedSprites;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
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
 * 单方块子层级（{@code VanillaSingleSubLevelRenderData}）的贴图动画恢复。
 *
 * <p>只有一格的载具（例如单独一个熔炉/岩浆块/装水的锅）走的是「单方块」渲染数据：它没有区块
 * 网格，也不调用 {@code renderChunkedSubLevel}，因此
 * {@link SableSubLevelSpriteAnimationMixin} 的补标记路径完全不会为它执行，Sodium 的
 * {@code animate_only_visible_textures} 于是让这一格贴图冻住。</p>
 *
 * <p>这里在 {@code renderSingleBlock} 的 HEAD 处把该方块（含流动流体与
 * {@code ModelData} 方块实体）用到的动画贴图交给 {@link SubLevelAnimatedSprites#markSingleBlock}
 * 标记；限频/失效都在那边（同一刻同一方块只算一次，位置或状态一变立即重算，资源重载随代次
 * 作废）。只读字段、不改渲染状态。</p>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData", remap = false)
public abstract class SableSingleBlockSpriteAnimationMixin {

    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Shadow
    private BlockState singleBlockState;

    @Shadow
    private BlockPos singleBlockPos;

    @Unique
    private final SubLevelAnimatedSprites dimblend$animatedSprites = new SubLevelAnimatedSprites();

    @Inject(method = "renderSingleBlock", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$markSingleBlockAnimatedSprites(RenderType layer, VertexConsumer consumer,
            Matrix4f modelView, double camX, double camY, double camZ, CallbackInfo ci) {
        if (!SubLevelAnimatedSprites.isSupported()) {
            return;
        }
        // 构造器里 rebuild() 之后两者才非空；字段本身可为 null（rebuild 前/世界切换中），必须挡。
        final BlockState state = this.singleBlockState;
        final BlockPos pos = this.singleBlockPos;
        final ClientSubLevel clientSubLevel = this.subLevel;
        if (state == null || pos == null || clientSubLevel == null) {
            return;
        }
        this.dimblend$animatedSprites.markSingleBlock(clientSubLevel.getLevel(), pos, state);
    }
}

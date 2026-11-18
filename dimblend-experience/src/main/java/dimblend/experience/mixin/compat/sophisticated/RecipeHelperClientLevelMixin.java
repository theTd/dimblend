package dimblend.experience.mixin.compat.sophisticated;

import dimblend.experience.compat.sophisticated.SophisticatedClientLevels;
import java.util.Optional;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端配方查询始终使用当前连接的世界。
 *
 * <p>目标：{@code net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper#getLevel()}（private static
 * {@code ()Ljava/util/Optional;}，字节码经 {@code javap -p -s} 核对）。上游只在
 * {@code LevelEvent.Load} 且维度为 {@code Level.OVERWORLD} 时 {@code setLevel}；本整合包
 * 玩家常驻 {@code dimblend:rotating}，客户端 overworld 从未加载，切石机/锻造升级的
 * {@code getRecipesOfType} / {@code safeGetRecipeFor} 因此静默返回空。</p>
 *
 * <p>只在缓存为空时兜底是不够的：缓存里可能留着上一个连接（甚至已切走维度前）的
 * {@code ClientLevel}，{@code WeakReference} 未 GC 就永远返回旧 {@code RecipeManager}。
 * 客户端线程上当前 {@code Minecraft.getInstance().level} 永远正确，因此无条件覆盖。
 * {@link SophisticatedClientLevels} 对服务端线程直接返回 {@code null}，服务端侧的
 * overworld 缓存不受影响。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper", remap = false)
public abstract class RecipeHelperClientLevelMixin {

    @Inject(
            method = "getLevel()Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 1,
            allow = 1)
    private static void dimblend$preferCurrentClientLevel(CallbackInfoReturnable<Optional<Level>> cir) {
        Level clientLevel = SophisticatedClientLevels.clientLevelForRecipeLookup();
        if (clientLevel != null) {
            cir.setReturnValue(Optional.of(clientLevel));
        }
    }
}

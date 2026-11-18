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
 * 客户端配方查询的 level 兜底。
 *
 * <p>目标：{@code net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper#getLevel()}（private static
 * {@code ()Ljava/util/Optional;}，字节码经 {@code javap -p -s} 核对）。上游只在
 * {@code LevelEvent.Load} 且维度为 {@code Level.OVERWORLD} 时 {@code setLevel}；本整合包
 * 玩家常驻 {@code dimblend:rotating}，客户端 overworld 从未加载，切石机/锻造升级的
 * {@code getRecipesOfType} / {@code safeGetRecipeFor} 因此静默返回空。</p>
 *
 * <p>缓存为空且当前在客户端线程时改用 {@code Minecraft.getInstance().level}。服务端线程
 * 的 overworld 缓存本来就有效，且 {@link SophisticatedClientLevels} 对服务端线程直接返回
 * {@code null}，不会把客户端世界塞进服务端侧缓存。</p>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.util.RecipeHelper", remap = false)
public abstract class RecipeHelperClientLevelMixin {

    @Inject(
            method = "getLevel()Ljava/util/Optional;",
            at = @At("RETURN"),
            cancellable = true,
            require = 1,
            allow = 1)
    private static void dimblend$fallbackToClientLevel(CallbackInfoReturnable<Optional<Level>> cir) {
        Optional<Level> cached = cir.getReturnValue();
        if (cached != null && cached.isPresent()) {
            return;
        }
        Level clientLevel = SophisticatedClientLevels.clientLevelForRecipeLookup();
        if (clientLevel != null) {
            cir.setReturnValue(Optional.of(clientLevel));
        }
    }
}

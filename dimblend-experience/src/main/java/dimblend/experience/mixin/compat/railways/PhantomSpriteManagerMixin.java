package dimblend.experience.mixin.compat.railways;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.api.DragonChaseClientState;
import dimblend.experience.compat.railways.PhantomTrackRevealRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * I24：追逐活动期间在 {@code dimblend:rotating} 强制显示幻影轨道（末地走廊的
 * {@code railways:track_phantom_wide}）。
 *
 * <p>目标类不在本模块编译 classpath 上，故 {@code targets} 字符串 + {@link Pseudo}；
 * 描述符按运行 jar railways-0.2.0-beta.2+neoforge-mc1.21.1 的 {@code javap -p -s} 实读：
 * {@code public static void tick(net.minecraft.client.Minecraft)}。{@code remap = false}：
 * 目标与描述符里的原版名字都是运行期名字。</p>
 *
 * <p>注入点是 {@code tick} 里两处
 * {@code CRTags$AllItemTags.matches(Lnet/minecraft/world/item/Item;)Z}
 * （主手/副手，字节码 #92，方法内仅此两处，故 {@code require = 2, allow = 2}）。
 * 把返回值与本 mod 的强制可见取或，等于直接改写上游算出来的
 * {@code visible = player != null && (mainMatches || offMatches)}，随后
 * {@code visible != lastVisible}、{@code lastVisible}、{@code hasChanged}、失效精灵清理
 * 全部仍由上游执行——追逐开始翻转一次、结束翻转一次，之后每 tick 不再置位
 * {@code hasChanged}，不会每帧重传图集帧。判定见
 * {@link PhantomTrackRevealRules}，不变量由 {@code PhantomTrackRevealRulesTest} 覆盖。</p>
 *
 * <p>为什么不在 RETURN 处覆盖：见 {@link PhantomTrackRevealRules} 的类注释。</p>
 */
@Pseudo
@Mixin(targets = "com.railwayteam.railways.content.custom_tracks.phantom.PhantomSpriteManager", remap = false)
public abstract class PhantomSpriteManagerMixin {

    @WrapOperation(
            method = "tick(Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/railwayteam/railways/registry/CRTags$AllItemTags;matches(Lnet/minecraft/world/item/Item;)Z"),
            require = 2, allow = 2)
    private static boolean dimblend$revealDuringChase(
            @Coerce Object tag, Item item, Operation<Boolean> original) {
        return PhantomTrackRevealRules.isRevealed(original.call(tag, item), dimblend$revealActive());
    }

    /** {@code DragonChaseClientState} 由 dimblend-dragonchase 的追逐状态包写入；未安装时恒 false。 */
    @Unique
    private static boolean dimblend$revealActive() {
        ClientLevel level = Minecraft.getInstance().level;
        boolean inRotating = level != null
                && ResourceLocation.fromNamespaceAndPath("dimblend", "rotating")
                        .equals(level.dimension().location());
        return PhantomTrackRevealRules.revealDuringChase(DragonChaseClientState.isChaseActive(), inRotating);
    }
}

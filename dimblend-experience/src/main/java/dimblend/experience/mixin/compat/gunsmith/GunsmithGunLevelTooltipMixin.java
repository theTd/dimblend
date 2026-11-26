package dimblend.experience.mixin.compat.gunsmith;

import dimblend.experience.compat.gunsmith.GunsmithData;
import dimblend.experience.compat.gunsmith.GunsmithLevelText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 TaCZ 原生 tooltip 的「等级」行显示本模组的真实枪匠等级（仅客户端）。
 *
 * <p><b>根因取证（2026-11-26）</b>：{@code com.tacz.guns.client.tooltip.ClientGunTooltip#getText()}
 * 用 {@code iGun.getLevel(gun)} / {@code getMaxLevel()} 拼这一行，而 TaCZ 的
 * {@code ModernKineticGunItem#getMaxLevel()} 是 {@code return 0} 的桩、
 * {@code getLevel(int exp)} / {@code getExp(int level)} 同样返回 0（CFR 反编译 + {@code javap -p} 已核），
 * 于是 {@code level >= maxLevel} 恒为 {@code 0 >= 0} → 每把枪都显示 {@code "0 (MAX)"}；
 * 那个 {@code 0} 跟本模组写在 {@code custom_data -> DimBlendGunsmithLevel} 的真实等级毫无关系
 * （本模组此前没有任何 tooltip 代码，gundb 的 {@code ClientGunTooltipMixin} 也只追加卡壳率一行）。</p>
 *
 * <p><b>为什么不在 TaCZ 的等级桩上做文章</b>：{@code getLevel(int exp)} 只拿得到 exp 值、
 * 拿不到 {@code ItemStack}，而 {@code getExpToNextLevel}/{@code getExpCurrentLevel} 也是桩，
 * 硬改会把非满级分支算成 {@code 0/0}；直接在 {@code getText} 收尾处覆写这一行的文本，
 * 既不动枪械行为逻辑，也不依赖任何未同步的配置（等级只从枪自己的 {@code CUSTOM_DATA} 读，双端一致）。</p>
 *
 * <p>注入点：{@code getText()V} 的 {@code TAIL}（该方法是 private，构造期由
 * {@code ClientGunTooltip} 构造函数调用一次，此时 {@code levelInfo}/{@code maxWidth} 已算好）。
 * 我们的文本永远不长于 TaCZ 原来的 {@code "0 (NaN%)"} / {@code "50 (MAX)"}，故不需要回写 {@code maxWidth}。</p>
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.tooltip.ClientGunTooltip", remap = false)
public abstract class GunsmithGunLevelTooltipMixin {

    @Shadow @Final private ItemStack gun;

    @Shadow private MutableComponent levelInfo;

    @Inject(method = "getText()V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$showGunsmithLevel(CallbackInfo ci) {
        ItemStack stack = this.gun;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int level = GunsmithData.getLevel(stack);
        ChatFormatting style = GunsmithLevelText.isMax(level) ? ChatFormatting.DARK_PURPLE : ChatFormatting.YELLOW;
        this.levelInfo = Component.translatable("tooltip.tacz.gun.level")
                .append(Component.literal(GunsmithLevelText.levelText(level)).withStyle(style));
    }
}

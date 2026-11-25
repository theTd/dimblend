package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.WaterItemPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 缺口 6：原版发射器（空桶 / 空玻璃瓶）舀世界水时补纯度。
 *
 * <p>{@code DispenseItemBehavior#bootStrap()}（{@code E:\misc\mcsrc-1.21.1}）里两条射水路径：
 * <ul>
 *   <li>{@code Items.BUCKET}（第 251-270 行）：匿名 {@code DefaultDispenseItemBehavior}，
 *       {@code bucketpickup.pickupBlock(...)} 后 {@code new ItemStack(item)} →
 *       {@code consumeWithRemainder(source, stack, rem)}（第 264 行）；</li>
 *   <li>{@code Items.GLASS_BOTTLE}（第 401-405 行）：匿名 {@code OptionalDispenseItemBehavior}
 *       的 {@code takeLiquid} 里同样 {@code consumeWithRemainder(source, stack, rem)}。</li>
 * </ul>
 * 两条都在 {@code new ItemStack(...)} / {@code PotionContents.createItemStack(...)} 里丢掉纯度，
 * 而 YAT 的 {@code MixinInventory} 会把无标签的水桶/水瓶归一成
 * {@code ThirstConfig.DEFAULT_PURITY = 2}（杀菌），构成"发射器洗水"漏洞。</p>
 *
 * <p>注入点选 {@code DefaultDispenseItemBehavior#consumeWithRemainder}（两条路径的共同出口，
 * 也是 {@code OptionalDispenseItemBehavior} 继承下来的那一个），一处覆盖两条；不碰匿名内部类
 * （{@code DispenseItemBehavior$N} 的编号随版本漂移，钉名字必炸）。目标方块位置按原版口径算：
 * {@code source.pos().relative(source.state().getValue(DispenserBlock.FACING))}。</p>
 *
 * <p>只在 {@code remainder} 是 YAT 认的水容器且<b>尚无</b> {@code Purity} 时才写（只补不覆盖）：
 * 射出水桶（remainder 是空桶）、蜂蜜瓶、奶桶等不满足 {@code isWaterFilledContainer}，一律不动。</p>
 */
@Mixin(DefaultDispenseItemBehavior.class)
public abstract class DispenseWaterPurityMixin {

    @WrapMethod(method = "consumeWithRemainder", require = 1, allow = 1)
    private ItemStack dimblend$stampDispensedWorldWater(BlockSource source, ItemStack dispensed, ItemStack remainder,
            Operation<ItemStack> original) {
        if (DirtyWaterCompat.purityAvailable()
                && source != null
                && remainder != null
                && !remainder.isEmpty()
                && WaterPurity.isWaterFilledContainer(remainder)
                && !WaterPurity.hasPurity(remainder)) {
            Level level = source.level();
            BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
            WaterItemPurity.write(remainder,
                    WaterSourcePurityRules.clampPurity(WaterPurity.getBlockPurity(level, target)));
        }
        return original.call(source, dispensed, remainder);
    }
}

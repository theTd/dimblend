package dimblend.experience.compat.dirtywater;

import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * YAT（{@code yet_another_thirst}）纯度的唯一读取/打标口。
 *
 * <p>所有引用 YAT 类型的代码集中在这里：脏水 mixin 由 {@code DimBlendMixinPlugin} 按
 * {@code yet_another_thirst} 在场性门控，YAT 缺席时本类不会被任何在跑的路径解析到，
 * 也不会被 JUnit（无 Bootstrap、YAT 不在测试 classpath）触达。</p>
 *
 * <p>物品与流体口径区分（实机 {@code common.toml} {@code defaultPurity = 2}）：
 * <ul>
 *   <li>物品容器（水桶/水瓶/水碗）走 {@link WaterPurity#getPurity(ItemStack)}：世界舀取的湖河水带
 *       {@code Purity: 0} 判脏，煮沸/过滤/无标签创造水桶（{@code >= 1}）放行；当玩家手持水桶/水瓶向
 *       工作盆或分液池倒水时，由 {@code GenericItemEmptyingDirtyWaterMixin} 把物品纯度写进产出的
 *       {@link FluidStack}；</li>
 *   <li>管网/储罐内的 {@link FluidStack}（Create 水泵抽河水、集水器、无限铜水槽等均不带 {@code Purity}
 *       标签）与 YAT {@code MixinFluidTank}、{@code FilterFrameBlockEntity$PurityBlendingTank} 及
 *       {@code CopperSinkBottleInteraction} 一致，无显式 {@code Purity} 标签一律按 0（脏水）计，必须经过
 *       YAT 滤水框打上 {@code Purity >= 1} 后才能进入工作盆或铜龙头。</li>
 * </ul></p>
 */
public final class DirtyWaterPurity {

    /** 物品是否为 YAT 识别的装水容器（水桶/水瓶/水碗）。 */
    public static boolean isWaterContainer(ItemStack stack) {
        return !stack.isEmpty() && WaterPurity.isWaterFilledContainer(stack);
    }

    /** 读取装水物品容器的纯度。 */
    public static int itemPurity(ItemStack stack) {
        return WaterPurity.getPurity(stack);
    }

    /** 把纯度写进水流体栈（原样返回该栈）。 */
    public static FluidStack stampFluidPurity(FluidStack stack, int purity) {
        if (!stack.isEmpty()) {
            FluidPurityHelper.addPurity(stack, purity);
        }
        return stack;
    }

    /** 物品里的水是否脏（水桶/水瓶/水碗一类 YAT 水容器）。 */
    public static boolean isDirtyWaterItem(ItemStack stack) {
        if (!isWaterContainer(stack)) {
            return false;
        }
        return DirtyWaterRules.isDirtyPurity(WaterPurity.getPurity(stack));
    }

    /** 流体栈是否是脏水（非水、空的流体一律 false；无显式 Purity 标签的原水按 0 判脏）。 */
    public static boolean isDirtyWaterFluid(FluidStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        int purity = DirtyWaterRules.effectiveFluidPurity(
                FluidPurityHelper.hasPurity(stack), FluidPurityHelper.getPurity(stack));
        return DirtyWaterRules.isDirtyWater(purity, BuiltInRegistries.FLUID.getKey(stack.getFluid()));
    }

    /** 方块位置的水纯度（世界水源/炼药锅；YAT 负责把 {@code BLOCK_PURITY = purity + 1} 解码）。 */
    public static int blockPurity(Level level, BlockPos pos) {
        return WaterPurity.getBlockPurity(level, pos);
    }

    private DirtyWaterPurity() {
    }
}

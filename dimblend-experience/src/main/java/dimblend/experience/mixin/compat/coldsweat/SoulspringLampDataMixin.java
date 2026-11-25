package dimblend.experience.mixin.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.momosoftworks.coldsweat.data.item_component.SoulspringLampData;

import dimblend.experience.compat.coldsweat.SoulspringLampRules;

/**
 * 灵魂之泉灯的默认燃料容量 64 → 256（LOCKED DECISION #15）。
 *
 * <p>两处默认值（{@code javap -c} 核实）：</p>
 * <ul>
 *   <li>{@code SoulspringLampData()} 无参构造体里的 {@code ldc2_w 64.0}——所有真实路径都走这里
 *       （物品的 {@code updateComponents} 发现缺组件时 new 一个，{@code getDefaultInstance} 再 setFuel）；</li>
 *   <li>codec 的 {@code optionalFieldOf("fuel", 64.0)} 在合成 lambda {@code lambda$static$0} 里；
 *       只有"组件存在但缺 fuel 字段"的畸形数据才会用到，所以这里用 {@code require = 0}：
 *       名字漂移时静默退化成 64（下一次 {@code setFuel} 就会写回 256），不让启动变红。</li>
 * </ul>
 *
 * <p>不能改 {@code coldsweat/item.toml}（用户配置），也不能碰 {@code ConfigSettings} 的任何 holder。</p>
 */
@Mixin(value = SoulspringLampData.class, remap = false)
public abstract class SoulspringLampDataMixin {

    /**
     * 无参构造器委托 {@code this(64.0, false)}，常量 {@code 64.0} 在 {@code invokespecial <init>(DZ)V}
     * 之前求值，此时 {@code this} 仍处于 {@code uninitializedThis} 状态——handler 必须为
     * {@code private static}，否则实例调用 {@code aload_0} 触发 JVM {@code VerifyError}。
     */
    @ModifyConstant(method = "<init>()V", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$defaultCapacity256(double original) {
        return SoulspringLampRules.CAPACITY;
    }

    @ModifyConstant(method = "lambda$static$0", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 0, allow = 1)
    private static double dimblend$codecDefaultCapacity256(double original) {
        return SoulspringLampRules.CAPACITY;
    }
}

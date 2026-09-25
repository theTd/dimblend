package dimblend.experience.compat.create;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import dimblend.experience.Config;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * 分液池管道补水（全局功能不限维度；保湿见 {@link ItemDrainIrrigation}，催熟见
 * {@link ItemDrainGrowthBoost}）：允许流体管道/泵向分液池内注水。
 *
 * <p>原版口径（Create 6.0.x {@code ItemDrainBlockEntity}，源码与 jar 字节码核验）：
 * 内置水箱 behaviour 注册为 {@code allowExtraction().forbidInsertion()}——管道只能
 * 抽不能灌（{@code InternalFluidHandler.fill} 首行检查 {@code insertionAllowed}）；
 * 且 {@code registerCapabilities} 对 {@code UP} 面返回 null——正上方接管道没有流体
 * 接口。本功能在开关开启时放开这两点，关闭即恢复原版行为：</p>
 *
 * <ol>
 * <li><b>注水门</b>：每 tick 双向置位（见 {@code ItemDrainPipeRefillMixin}）——开关开
 * → 置回允许，开关关 → 置回禁止（原版常态），配置热切换两向都在下一拍生效，不依赖
 * 方块重建/区块重载。不采用“建时改一次标志”的原因：{@code ItemDrainBlockEntity.
 * continueProcessing()} 每次倒空流体物品后都会 {@code forbidInsertion()} 复位，
 * 一次性放开会被物品加工悄悄关回；逐 tick 双向置位对任何标志漂移都自愈。
 * 已知 1-tick 窗口：正在加工流体物品的当拍，物品排空收尾的 {@code forbidInsertion}
 * 发生在同一 {@code tick()} 内、本头置位注入之后——加工期间每拍收尾关闭、下一拍自愈，
 * 同拍里晚于分液池 tick 的管道传输会空一拍；无物品加工的灌溉补水水箱不存在此窗口。</li>
 * <li><b>UP 面接口</b>：向 NeoForge 追加注册一个仅 {@code UP} 面生效的
 * {@code FluidHandler} provider。{@code BlockCapability} 查询是“首个非 null 生效、
 * null 落穿”语义（{@code RegisterCapabilitiesEvent} 允许多 provider 同注册），
 * 追加注册对 Create 自己的 provider 及注册顺序均无影响。返回的 handler 是水箱
 * behaviour 的公开 capability，与侧面/底面同一实例，抽取与注水门控完全一致。
 * 已知限制：配置热切换不主动调用 {@code Level.invalidateCapabilities}，用
 * {@code BlockCapabilityCache} 缓存“UP 面接口存在性”的第三方消费者可能滞留至
 * 方块更新/重载；注水门控在 handler 内逐次判标志，滞留缓存下 fill 仍会被拒，
 * 残余影响仅存在性判定——对缓存型消费者（如 Create 管道）而言存在性即传输触发
 * 开关，实际观感是“热开启后顶面管道可能需方块更新才开始注水”。</li>
 * </ol>
 *
 * <p>调用方：mixin 每 tick 调 {@link #openInsertion}/{@link #closeInsertion}；
 * {@link #registerCapabilities} 由 {@code DimBlend} 在 Create 在场时挂到 mod 总线。</p>
 */
public final class ItemDrainPipeRefill {

    /** 置回“允许注水”（调用方保证：服务端、开关已开；逐 tick 幂等）。 */
    public static void openInsertion(ItemDrainBlockEntity be) {
        SmartFluidTankBehaviour tank = tankOf(be);
        if (tank != null) {
            tank.allowInsertion();
        }
    }

    /** 置回“禁止注水”（调用方保证：服务端、开关已关）= 原版常态；与 {@link #openInsertion} 构成双向自愈。 */
    public static void closeInsertion(ItemDrainBlockEntity be) {
        SmartFluidTankBehaviour tank = tankOf(be);
        if (tank != null) {
            tank.forbidInsertion();
        }
    }

    /**
     * 追加注册 UP 面流体接口。开关关闭、配置未加载（客户端未同步等，{@code Value#get()}
     * 会抛 ISE）、非 UP 面、或查不到水箱 behaviour 时返回 null，落穿给 Create 自己的
     * 注册结果（即原版：UP 面无接口、其余面原门控）。
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                AllBlockEntityTypes.ITEM_DRAIN.get(),
                (be, context) -> {
                    if (context != Direction.UP || !Config.isLoaded() || !Config.ITEM_DRAIN_PIPE_REFILL.get()) {
                        return null;
                    }
                    SmartFluidTankBehaviour tank = tankOf(be);
                    return tank == null ? null : tank.getCapability();
                });
    }

    /** 分液池的水箱 behaviour（Create 公开 API {@code SmartBlockEntity#getBehaviour}）。 */
    private static SmartFluidTankBehaviour tankOf(ItemDrainBlockEntity be) {
        return be.getBehaviour(SmartFluidTankBehaviour.TYPE);
    }

    private ItemDrainPipeRefill() {
    }
}

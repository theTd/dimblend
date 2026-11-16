package dimblend.experience.compat.coldsweat;

import com.momosoftworks.coldsweat.common.block.ThermolithBlock;
import com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import dimblend.experience.mixin.compat.coldsweat.ThermolithBlockEntityAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 恒温石模式规则：方块状态属性、右键切换、模式驱动红石信号。
 *
 * <p>本类的静态初始化只建一个 {@link EnumProperty}（纯数据，无注册表依赖），纯函数部分
 * （{@link #signalFor}、{@link #nextMode}、{@link #resetMode}、阈值）由
 * {@code ColdSweatBatch1116Test} 直接单测；Cold Sweat 类型只出现在方法签名/方法体内，
 * 类加载不需要 Cold Sweat 在场。</p>
 *
 * <p>口径（Cold Sweat 的 MC 温度单位）：{@code Temperature.convert(c, C, MC)} = c/25，
 * 所以 40 °C = 1.6 MC、10 °C = 0.4 MC。冰模式要求<b>严格高于</b> 40 °C，熔岩模式要求
 * <b>严格低于</b> 10 °C，正好等于阈值时不输出（与需求表一致）。</p>
 *
 * <p>切换走 {@link PlayerInteractEvent.RightClickBlock} 而不是 mixin 覆写
 * {@code useItemOn}/{@code useWithoutItem}：本仓库 Mixin 纪律禁止 mixin 类出现非 private
 * 成员（覆写会 merge 进目标类），事件订阅零 merge 风险。客户端同侧取消的意义：
 * {@code MultiPlayerGameMode.performUseItemOn} 在取消时直接返回，客户端不会本地预演
 * "把手上的冰块贴上去"；{@code ServerboundUseItemOnPacket} 在取消前就已排入发送队列，
 * 服务端照常收到并执行切换。</p>
 */
public final class ThermolithModeRules {

    /** 方块状态属性名（进 {@code cold_sweat:blockstates/thermolith.json} 的变体键）。 */
    public static final String MODE_PROPERTY_NAME = "dimblend_mode";

    /** 三值模式属性；{@code NORMAL} 是首常量 = 默认态。 */
    public static final EnumProperty<ThermolithMode> MODE =
            EnumProperty.create(MODE_PROPERTY_NAME, ThermolithMode.class);

    /** 冰模式阈值：40 °C = 1.6 MC。 */
    public static final double ICE_THRESHOLD_MC = celsiusToMc(40.0);
    /** 熔岩模式阈值：10 °C = 0.4 MC。 */
    public static final double MAGMA_THRESHOLD_MC = celsiusToMc(10.0);
    /** 满足模式条件时输出的满红石信号。 */
    public static final int FULL_SIGNAL = 15;

    /**
     * 冰的判定/回退口径（可在运行期通过 {@link #ACTIVE_POLICY} 切换，供整合包按需回退）：
     * <ul>
     *   <li>{@code VANILLA_ICE_TOGGLE_RESET}：只有原版冰触发；再点一次冰回到 {@code NORMAL}；
     *       潜行空手可复位</li>
     *   <li>{@code ANY_ICE_TOGGLE_RESET}：原版冰 + 浮冰 + 蓝冰都触发；其余同上</li>
     *   <li>{@code VANILLA_ICE_BREAK_ONLY_RESET}：只有原版冰触发；<b>只能靠拆方块</b>退出
     *       冰/熔岩模式（右键不切换、潜行空手不复位）</li>
     * </ul>
     */
    public enum InteractionPolicy {
        VANILLA_ICE_TOGGLE_RESET(true, false),
        ANY_ICE_TOGGLE_RESET(true, true),
        VANILLA_ICE_BREAK_ONLY_RESET(false, false);

        private final boolean togglesBackToNormal;
        private final boolean acceptsAnyIce;

        InteractionPolicy(boolean togglesBackToNormal, boolean acceptsAnyIce) {
            this.togglesBackToNormal = togglesBackToNormal;
            this.acceptsAnyIce = acceptsAnyIce;
        }

        /** 再点同一种材料是否回到 {@code NORMAL}（false = 只能拆方块复位）。 */
        public boolean togglesBackToNormal() {
            return this.togglesBackToNormal;
        }

        /** 浮冰/蓝冰是否等价于原版冰。 */
        public boolean acceptsAnyIce() {
            return this.acceptsAnyIce;
        }
    }

    /** 当前生效口径：2026-11-16 锁定仅原版冰/熔岩块单向设模式、仅拆掉重放恢复默认。 */
    public static volatile InteractionPolicy ACTIVE_POLICY = InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET;

    /** Celsius → Cold Sweat MC 温度单位（Cold Sweat {@code Temperature.convert} 的 C→MC 分支）。 */
    public static double celsiusToMc(double celsius) {
        return celsius / 25.0;
    }

    /**
     * 模式决定的红石信号（纯函数）。
     *
     * @param mode          当前模式；{@code NORMAL} 由原版 tick 负责，这里返回 0
     * @param temperatureMc {@code WorldHelper.getTemperatureAt} 给出的 MC 温度
     */
    public static int signalFor(ThermolithMode mode, double temperatureMc) {
        return switch (mode) {
            case ICE -> temperatureMc > ICE_THRESHOLD_MC ? FULL_SIGNAL : 0;
            case MAGMA -> temperatureMc < MAGMA_THRESHOLD_MC ? FULL_SIGNAL : 0;
            case NORMAL -> 0;
        };
    }

    /**
     * 手持材料点击后的目标模式（纯函数）。
     *
     * @param current   点击前方块模式
     * @param requested 手上材料对应的模式（{@code ICE}/{@code MAGMA}；{@code NORMAL} 视为无请求）
     * @param policy    当前口径
     */
    public static ThermolithMode nextMode(ThermolithMode current, ThermolithMode requested, InteractionPolicy policy) {
        if (requested == ThermolithMode.NORMAL) {
            return current;
        }
        return current == requested && policy.togglesBackToNormal() ? ThermolithMode.NORMAL : requested;
    }

    /** 潜行空手复位后的模式（纯函数）：不支持的策略保持原样。 */
    public static ThermolithMode resetMode(ThermolithMode current, InteractionPolicy policy) {
        return policy.togglesBackToNormal() && current != ThermolithMode.NORMAL ? ThermolithMode.NORMAL : current;
    }

    /**
     * 订阅模式切换事件。由 {@code DimBlend} 在构造期调用一次（Cold Sweat 缺席时直接返回，
     * 本类不因此被跳过——静态初始化不碰 Cold Sweat 类型）。
     */
    public static void register() {
        if (!ModList.get().isLoaded("cold_sweat")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ThermolithModeRules::onRightClickBlock);
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ThermolithBlock) || !state.hasProperty(MODE)) {
            return;
        }

        ThermolithMode current = state.getValue(MODE);
        ItemStack held = event.getItemStack();
        InteractionPolicy policy = ACTIVE_POLICY;
        ThermolithMode next;

        if (matchesIce(held, policy)) {
            next = nextMode(current, ThermolithMode.ICE, policy);
        } else if (held.is(Items.MAGMA_BLOCK)) {
            next = nextMode(current, ThermolithMode.MAGMA, policy);
        } else if (held.isEmpty()
                && event.getHand() == InteractionHand.MAIN_HAND
                && event.getEntity().isShiftKeyDown()
                && current != ThermolithMode.NORMAL
                && policy.togglesBackToNormal()) {
            next = ThermolithMode.NORMAL;
        } else {
            return;
        }

        if (next != current) {
            BlockState updated = state.setValue(MODE, next);
            if (!level.isClientSide) {
                level.setBlockAndUpdate(pos, updated);
                // 点击当 tick 就重算信号：否则要等原版 20 tick 的节拍，红石线会闪一下延迟。
                if (level.getBlockEntity(pos) instanceof ThermolithBlockEntity thermolith) {
                    evaluateAndApply(level, pos, updated, thermolith);
                }
                level.playSound(null, pos,
                        next == ThermolithMode.ICE ? SoundEvents.GLASS_PLACE : SoundEvents.STONE_PLACE,
                        SoundSource.BLOCKS, 0.8F, next == ThermolithMode.ICE ? 1.4F : 0.9F);
            }
        }

        // 两侧都取消：客户端不预演放冰/放岩浆，服务端拿到包自己执行。
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private static boolean matchesIce(ItemStack stack, InteractionPolicy policy) {
        if (stack.is(Items.ICE)) {
            return true;
        }
        return policy.acceptsAnyIce() && (stack.is(Items.PACKED_ICE) || stack.is(Items.BLUE_ICE));
    }

    /**
     * 按当前模式重算信号并写回恒温石（服务端调用）。
     *
     * <p>先写 {@code signal} 字段（{@code ThermolithBlock#getSignal} 直接读它，
     * 只在朝向背面输出），信号变化才像原版那样通知朝向邻居；再按 {@code signal != 0}
     * 同步 {@code POWERED} 方块态（驱动亮度和 on/off 贴图）。</p>
     */
    public static void evaluateAndApply(Level level, BlockPos pos, BlockState state, ThermolithBlockEntity thermolith) {
        if (!state.hasProperty(MODE) || !state.hasProperty(ThermolithBlock.POWERED)) {
            return;
        }
        ThermolithMode mode = state.getValue(MODE);
        double temperature = WorldHelper.getTemperatureAt(level, pos);
        int signal = mode == ThermolithMode.NORMAL
                ? (int) CSMath.blend(0.0, 15.0, temperature, ConfigSettings.MIN_TEMP.get(), ConfigSettings.MAX_TEMP.get())
                : signalFor(mode, temperature);
        ThermolithBlockEntityAccessor access = (ThermolithBlockEntityAccessor) thermolith;
        if (access.dimblend$getSignal() != signal) {
            access.dimblend$setSignal(signal);
            ThermolithBlock.updateFacingNeighbors(level, state, pos);
        }

        boolean powered = signal != 0;
        if (state.getValue(ThermolithBlock.POWERED) != powered) {
            level.setBlockAndUpdate(pos, state.setValue(ThermolithBlock.POWERED, powered));
        }
    }

    private ThermolithModeRules() {
    }
}

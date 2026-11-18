package dimblend.experience.compat.coldsweat;

import com.momosoftworks.coldsweat.common.block.ThermolithBlock;
import com.momosoftworks.coldsweat.common.blockentity.ThermolithBlockEntity;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.util.math.CSMath;
import com.momosoftworks.coldsweat.util.world.WorldHelper;
import dimblend.experience.mixin.compat.coldsweat.ThermolithBlockEntityAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
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
 * 所以 30 °C = 1.2 MC、10 °C = 0.4 MC。冰模式要求<b>严格高于</b> 30 °C，熔岩模式要求
 * <b>严格低于</b> 10 °C，正好等于阈值时不输出（与需求表一致）。</p>
 *
 * <p>红石抑制：温石只在 {@code FACING} 侧输出信号，若直接用 {@code Level#hasNeighborSignal}
 * 会把自己点亮的那一侧读回来（自反馈振荡）。{@link #hasExternalNeighborSignal} 因此跳过
 * 输出侧的普通红石粉（它只回放温石自己的 15），其余五面照常读取；输出侧的其他信号源
 * （红石块、火把、拉杆、朝内的中继器/比较器）用 {@code getDirectSignal} 读取，不会被温石
 * 自己的输出污染。抑制在三模式下都生效，输出强制为 0。</p>
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

    /** 抑制属性名（进 {@code cold_sweat:blockstates/thermolith.json} 的变体键）。 */
    public static final String SUPPRESSED_PROPERTY_NAME = "dimblend_suppressed";

    /**
     * 邻近外源红石信号时置 true：输出被强制为 0、亮度熄灭、客户端喷红色粉尘。
     * {@code false} 是首常量 = 默认态。
     */
    public static final BooleanProperty DIMBLEND_SUPPRESSED = BooleanProperty.create(SUPPRESSED_PROPERTY_NAME);

    /** 抑制提示翻译键（物品 tooltip，灰色）。 */
    public static final String SUPPRESSED_TOOLTIP_KEY = "tooltip.dimblend_experience.thermolith.redstone_stop";

    private static final ResourceLocation THERMOLITH_ID =
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "thermolith");

    /** 冰模式阈值：30 °C = 1.2 MC（严格高于）。 */
    public static final double ICE_THRESHOLD_MC = celsiusToMc(30.0);
    /** 熔岩模式阈值：10 °C = 0.4 MC（严格低于）。 */
    public static final double MAGMA_THRESHOLD_MC = celsiusToMc(10.0);
    /** 满足模式条件时输出的满红石信号。 */
    public static final int FULL_SIGNAL = 15;

    /** 原版节拍：温度重算每 20 tick 一次（与原版 {@code ThermolithBlockEntity#tick} 一致）。 */
    private static final long TEMPERATURE_INTERVAL_TICKS = 20L;

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
     * @param mode          当前模式；{@code NORMAL} 走原版线性插值（不经过本函数），这里返回 0
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
     * 订阅模式切换与物品提示事件。由 {@code DimBlend} 在构造期调用一次（Cold Sweat 缺席时
     * 直接返回，本类不因此被跳过——静态初始化不碰 Cold Sweat 类型）。
     */
    public static void register() {
        if (!ModList.get().isLoaded("cold_sweat")) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ThermolithModeRules::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(ThermolithModeRules::onItemTooltip);
    }

    /**
     * 恒温石物品提示补一行"通入信号停止工作"。
     *
     * <p>走 {@link ItemTooltipEvent} 而不是 mixin 覆写 {@code appendHoverText}：Cold Sweat
     * 的 {@code ThermolithBlock} 没有覆写该方法（{@code Block#appendHoverText} 是唯一的实现），
     * {@code @Inject} 到不存在的目标方法会直接让 mixin 应用失败。按注册名匹配，不引用
     * Cold Sweat 类型。</p>
     */
    private static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !THERMOLITH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
            return;
        }
        event.getToolTip().add(Component.translatable(SUPPRESSED_TOOLTIP_KEY).withStyle(ChatFormatting.GRAY));
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
     * 每 tick 的重算入口（服务端调用；客户端只读同步来的方块态）。
     *
     * <p>抑制判定每 tick 都做——红石输入变化必须在同一 tick 见效；温度重算仍是原版的
     * 20 tick 节拍（{@code WorldHelper.getTemperatureAt} 不便宜，且温度本来就不会瞬变）。
     * 抑制状态翻转时强制补算一次温度信号，否则解除抑制后要等到下一个 20 tick 才恢复出力。</p>
     */
    public static void tick(Level level, BlockPos pos, BlockState state, ThermolithBlockEntity thermolith) {
        if (level.isClientSide) {
            return;
        }
        apply(level, pos, state, thermolith, level.getGameTime() % TEMPERATURE_INTERVAL_TICKS == 0L);
    }

    /**
     * 按当前模式重算信号并写回恒温石（服务端调用；模式切换当 tick 立即调用）。
     *
     * <p>先写 {@code signal} 字段（{@code ThermolithBlock#getSignal} 直接读它，
     * 只在朝向背面输出），信号变化才像原版那样通知朝向邻居；再按 {@code signal != 0}
     * 同步 {@code POWERED} 与 {@code DIMBLEND_SUPPRESSED} 方块态（驱动亮度和 on/off 贴图）。</p>
     */
    public static void evaluateAndApply(Level level, BlockPos pos, BlockState state, ThermolithBlockEntity thermolith) {
        apply(level, pos, state, thermolith, true);
    }

    private static void apply(Level level, BlockPos pos, BlockState state, ThermolithBlockEntity thermolith,
            boolean temperatureDue) {
        if (!state.hasProperty(MODE)
                || !state.hasProperty(ThermolithBlock.POWERED)
                || !state.hasProperty(DIMBLEND_SUPPRESSED)) {
            return;
        }
        boolean wasSuppressed = state.getValue(DIMBLEND_SUPPRESSED);
        boolean suppressed = hasExternalNeighborSignal(level, pos, state);
        ThermolithBlockEntityAccessor access = (ThermolithBlockEntityAccessor) thermolith;

        int signal;
        if (suppressed) {
            signal = 0;
        } else if (temperatureDue || wasSuppressed) {
            signal = modeSignal(level, pos, state.getValue(MODE));
        } else {
            signal = access.dimblend$getSignal();
        }

        if (access.dimblend$getSignal() != signal) {
            access.dimblend$setSignal(signal);
            ThermolithBlock.updateFacingNeighbors(level, state, pos);
        }

        boolean powered = signal != 0;
        if (state.getValue(ThermolithBlock.POWERED) != powered || wasSuppressed != suppressed) {
            level.setBlockAndUpdate(pos, state
                    .setValue(ThermolithBlock.POWERED, powered)
                    .setValue(DIMBLEND_SUPPRESSED, suppressed));
        }
    }

    /** 模式自身的信号：{@code NORMAL} 复刻原版按温度线性插值，冰/熔岩走二值阈值。 */
    private static int modeSignal(Level level, BlockPos pos, ThermolithMode mode) {
        if (mode == ThermolithMode.NORMAL) {
            double temperature = WorldHelper.getTemperatureAt(level, pos);
            return (int) CSMath.blend(0.0, 15.0, temperature,
                    ConfigSettings.MIN_TEMP.get(), ConfigSettings.MAX_TEMP.get());
        }
        return signalFor(mode, WorldHelper.getTemperatureAt(level, pos));
    }

    /**
     * 邻居里是否有"外源"红石信号（排除温石自己的输出回放）。
     *
     * <p>温石只在 {@code FACING} 侧输出：该侧的红石粉会带着温石自己的 15 回读，因此跳过；
     * 输出侧的其他信号源（红石块、火把、拉杆、按钮、朝内的中继器/比较器/观察者）用
     * {@code getDirectSignal} 读取，它们不会反射温石自己的输出。其余五面用
     * {@code getSignal}（含红石导体的 {@code getDirectSignalTo} 聚合）。</p>
     */
    public static boolean hasExternalNeighborSignal(Level level, BlockPos pos, BlockState state) {
        Direction outputSide = state.getValue(ThermolithBlock.FACING);
        int[] signals = new int[Direction.values().length];
        boolean outputSideIsWire = false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (direction == outputSide) {
                outputSideIsWire = level.getBlockState(neighbour).getBlock() instanceof RedStoneWireBlock;
                signals[direction.get3DDataValue()] = outputSideIsWire
                        ? 0
                        : level.getDirectSignal(neighbour, direction);
            } else {
                signals[direction.get3DDataValue()] = level.getSignal(neighbour, direction);
            }
        }
        return isSuppressed(signals, outputSide, outputSideIsWire);
    }

    /**
     * 抑制判定的纯函数（单测入口）。
     *
     * @param neighbourSignals 六个方向邻居朝温石这一侧的信号，下标为
     *                         {@link Direction#get3DDataValue()}
     * @param outputSide       温石的输出侧（{@code FACING}）；该侧是红石粉时整侧忽略
     * @param outputSideIsWire 输出侧邻居是否是普通红石粉
     */
    public static boolean isSuppressed(int[] neighbourSignals, Direction outputSide, boolean outputSideIsWire) {
        for (Direction direction : Direction.values()) {
            int signal = neighbourSignals[direction.get3DDataValue()];
            if (direction == outputSide) {
                if (!outputSideIsWire && signal > 0) {
                    return true;
                }
                continue;
            }
            if (signal > 0) {
                return true;
            }
        }
        return false;
    }

    private ThermolithModeRules() {
    }
}

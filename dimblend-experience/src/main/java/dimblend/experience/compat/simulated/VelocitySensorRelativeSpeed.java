package dimblend.experience.compat.simulated;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 速度传感器显示连接器的两个新选项（2026-11-26 slice-1 #1A）。
 *
 * <p>机制取证（{@code simulated-neoforge-1.21.1-1.3.0.jar}，{@code javap -p -c}）：</p>
 * <ul>
 *   <li>{@code VelocitySensorDisplaySource} 只有一个"模式"下拉框，由
 *       {@code AbstractNumericDisplaysource#initConfigurationWidgets} 用
 *       {@code builder.addSelectionScrollInput(0, getWidth(), (input, label) -> input.forOptions(getOptions()),
 *       getSelectionKey())} 建出，<b>只在非首行（{@code isFirstLine == false}）</b>出现；</li>
 *   <li>选中值以 {@code int} 存进 {@code DisplayLinkBlockEntity} 的 {@code sourceConfig}
 *       （NBT 键 {@code "VeclotySensorSelection"}，上游拼写错误，必须原样沿用）；</li>
 *   <li>{@code getOptions()} 目前只返回 {@code SimLang.translatedOptions("display_source.velocity_sensor", "speed")}
 *       一项（= 中文"相对速度"），{@code provideLine} 里 {@code 0 → m/s}，其余值一律返回 {@code ZERO}。</li>
 * </ul>
 *
 * <p>所以这里只在<b>末尾追加</b>两项（index 1 = km/h、2 = mph），index 0 的 m/s 语义逐字节不变：
 * 老存档里 {@code VeclotySensorSelection = 0}（或键缺失）继续显示原来的 m/s，
 * 新增的两项不会被旧配置误命中。</p>
 *
 * <p>{@code getAdjustedVelocity()} 用反射取：{@code simulated} 只是运行时 jar-in-jar，
 * 不是编译期依赖（{@code dimblend-experience/build.gradle} 里没有 simulated），
 * 硬导入 {@code dev.simulated_team.simulated.*} 会编译失败。</p>
 */
public final class VelocitySensorRelativeSpeed {

    /** {@code VelocitySensorDisplaySource#getSelectionKey()} 的返回值（上游拼写错误，保持原样）。 */
    public static final String SELECTION_KEY = "VeclotySensorSelection";

    public static final int MODE_METRES_PER_SECOND = 0;
    public static final int MODE_KMH = 1;
    public static final int MODE_MPH = 2;

    public static final String KMH_LANG = "dimblend_experience.display_source.velocity_kmh";
    public static final String MPH_LANG = "dimblend_experience.display_source.velocity_mph";

    private static final String ADJUSTED_VELOCITY_METHOD = "getAdjustedVelocity";

    private static volatile Method adjustedVelocityGetter;

    /** 原选项表 + 两项新选项，顺序固定（1 = km/h、2 = mph）。 */
    public static List<Component> withRelativeSpeedOptions(List<Component> original) {
        List<Component> options = new ArrayList<>(original.size() + 2);
        options.addAll(original);
        options.add(Component.translatable(KMH_LANG));
        options.add(Component.translatable(MPH_LANG));
        return List.copyOf(options);
    }

    public static boolean isRelativeSpeedMode(int mode) {
        return mode == MODE_KMH || mode == MODE_MPH;
    }

    /** 只在 {@link #isRelativeSpeedMode(int)} 为真时调用。 */
    public static MutableComponent line(int mode, float metresPerSecond) {
        String text = mode == MODE_MPH
                ? VelocityUnitConversion.milesPerHourText(metresPerSecond)
                : VelocityUnitConversion.kilometresPerHourText(metresPerSecond);
        return Component.literal(text);
    }

    /**
     * 读 {@code VelocitySensorBlockEntity#getAdjustedVelocity()F}。
     *
     * <p>取不到（目标不是速度传感器方块实体、方法签名变了、反射被拦）一律回 0，
     * 让显示屏显示 {@code 0.0 km/h} 而不是崩：这条路每个显示刷新周期只跑一次
     * （{@code DisplaySource#getPassiveRefreshTicks()} 默认 100 tick）。</p>
     */
    public static float adjustedVelocity(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return 0.0F;
        }
        Method getter = adjustedVelocityGetter;
        if (getter == null || !getter.getDeclaringClass().isInstance(blockEntity)) {
            try {
                getter = blockEntity.getClass().getMethod(ADJUSTED_VELOCITY_METHOD);
            } catch (NoSuchMethodException missing) {
                return 0.0F;
            } catch (SecurityException denied) {
                return 0.0F;
            }
            adjustedVelocityGetter = getter;
        }
        try {
            Object value = getter.invoke(blockEntity);
            return value instanceof Number number ? number.floatValue() : 0.0F;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return 0.0F;
        }
    }

    private VelocitySensorRelativeSpeed() {
    }
}

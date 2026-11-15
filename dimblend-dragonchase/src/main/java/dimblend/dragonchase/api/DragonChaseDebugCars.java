package dimblend.dragonchase.api;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.DragonCars;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * 从模块外生成一节非巢车厢（2026-11-15，创造调试用）。
 *
 * <p>唯一用途是让 {@code dimblend-experience} 的驾驶台调试预设能按真实路径生成刹车/水晶车厢：
 * {@link DragonCars#buildBrakeCar} 会在装配后给刹车信号通电（灯亮、转向架制动），
 * {@link DragonCars#buildCrystalCar} 会种下 1-4 颗末影水晶，并都按正常流程登记到
 * {@code DragonCarriages}。这里不复制任何装配逻辑，只把分派收口在 {@code .api} 包内，避免跨模块引用内部枚举。
 *
 * <p><b>龙巢不开放。</b>它是追逐关卡自身的结构（强制加载、奖励分区、只由导演生成），
 * 不提供生成方法且不出现在 {@link #debugCarIds()} 中，避免调试接口变成第二条龙巢生成路径。
 *
 * <p>调用方必须自行确认区块已加载（{@link DragonCars#footprintReady}），装配器在区块未就绪时
 * 返回 {@code null} 而不是同步加载主线程。
 */
public final class DragonChaseDebugCars {

    /**
     * 所有开放给创造调试预设的非龙巢车厢 ID（{@link CarKind} 枚举名小写，排除 {@link CarKind#NEST}）。
     */
    public static List<String> debugCarIds() {
        List<String> ids = new ArrayList<>();
        for (CarKind kind : CarKind.values()) {
            if (kind != CarKind.NEST) {
                ids.add(kind.name().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 在主线（{@code Z=0, Y=64}）上的中心 X 静止生成一节刹车车厢。
     *
     * @return 装配出的 sub-level，或区块未就绪/装配失败时的 {@code null}
     */
    @Nullable
    public static ServerSubLevel spawnBrake(ServerLevel level, int centerX) {
        return DragonCars.buildBrakeCar(level, centerX, 0.0);
    }

    /**
     * 在主线（{@code Z=0, Y=64}）上的中心 X 静止生成一节水晶车厢。
     *
     * @return 装配出的 sub-level，或区块未就绪/装配失败时的 {@code null}
     */
    @Nullable
    public static ServerSubLevel spawnCrystal(ServerLevel level, int centerX) {
        return DragonCars.buildCrystalCar(level, centerX, 0.0);
    }

    private DragonChaseDebugCars() {
    }
}

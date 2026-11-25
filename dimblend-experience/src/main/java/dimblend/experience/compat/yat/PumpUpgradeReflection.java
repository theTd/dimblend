package dimblend.experience.compat.yat;

import java.lang.reflect.Method;

import dimblend.experience.DimBlend;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Sophisticated Core {@code PumpUpgradeWrapper} 导出侧反射桥（无编译期依赖）。
 *
 * <p>{@code sophisticatedcore} 不在 {@code dimblend-experience} 的 {@code compileOnly}
 * 依赖内，{@code PumpUpgradeWrapperPurityMixin} 为 {@code @Pseudo}，因此把
 * {@code getFluidFilterLogic()} / {@code fluidMatches(FluidStack)} 的反射缓存收拢在
 * 非 mixin 包下，符合 {@code AGENTS.md} "mixin 包内不放辅助类"纪律。</p>
 */
public final class PumpUpgradeReflection {

    private static volatile Method getFluidFilterLogicMethod;
    private static volatile Method fluidMatchesMethod;
    private static volatile boolean failed;

    /** 调用 {@code wrapper.getFluidFilterLogic().fluidMatches(fluid)}；反射失败时返回 null 表示回退原实现。 */
    public static Boolean fluidMatches(Object pumpWrapper, FluidStack fluid) {
        if (failed || pumpWrapper == null) {
            return null;
        }
        try {
            Method getFilter = getFluidFilterLogicMethod;
            if (getFilter == null) {
                getFilter = pumpWrapper.getClass().getMethod("getFluidFilterLogic");
                getFluidFilterLogicMethod = getFilter;
            }
            Object filter = getFilter.invoke(pumpWrapper);
            if (filter == null) {
                return Boolean.FALSE;
            }
            Method matches = fluidMatchesMethod;
            if (matches == null) {
                matches = filter.getClass().getMethod("fluidMatches", FluidStack.class);
                fluidMatchesMethod = matches;
            }
            Object res = matches.invoke(filter, fluid);
            return res instanceof Boolean b ? b : Boolean.FALSE;
        } catch (ReflectiveOperationException e) {
            failed = true;
            DimBlend.LOGGER.warn("Failed to reflect PumpUpgradeWrapper#getFluidFilterLogic/fluidMatches; falling back", e);
            return null;
        }
    }

    private PumpUpgradeReflection() {
    }
}

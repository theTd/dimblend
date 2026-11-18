package dimblend.experience.compat.fluid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 铜龙头（{@code fluid:copper_tap}）对正下方森罗物语厨房（{@code kaleidoscope_cookery}）
 * 汤锅（{@code stockpot}）与茶壶/水壶（{@code teapot}）的注水支持。
 *
 * <p>森罗物语厨房不进入 {@code dimblend-experience} 的编译期常规依赖，因此通过一次性缓存反射访问
 * {@code StockpotBlockEntity} 与 {@code TeapotBlockEntity} 的状态字段和 {@code refresh()}。</p>
 */
public final class CookeryCopperTapSupport {

    public static final int FILL_AMOUNT_MB = 1000;

    private static final String STOCKPOT_BE_CLASS =
            "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity";
    private static final String MOD_SOUP_BASES_CLASS =
            "com.github.ysbbbbbb.kaleidoscopecookery.init.ModSoupBases";
    private static final String TEAPOT_BE_CLASS =
            "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity";
    private static final String TEAPOT_SERIALIZER_CLASS =
            "com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.TeapotRecipeSerializer";

    private static volatile boolean initialized;
    private static volatile boolean available;

    private static Class<?> stockpotClass;
    private static Method stockpotHasLid;
    private static Method stockpotGetStatus;
    private static Method stockpotSetStatus;
    private static Method stockpotRefresh;
    private static Field stockpotSoupBaseId;
    private static ResourceLocation waterSoupBaseId;

    private static Class<?> teapotClass;
    private static Method teapotGetStatus;
    private static Method teapotGetTeaFluidId;
    private static Method teapotRefresh;
    private static Field teapotTeaFluidId;
    private static ResourceLocation emptyTeaFluidId;

    /** 铜龙头一次注水的处理结果。 */
    public enum TapFillStatus {
        /** 正下方不是汤锅/茶壶（或状态不允许注水）：交给原版逻辑处理。 */
        NOT_APPLICABLE,
        /** 已把 {@link #FILL_AMOUNT_MB} 水注入锅/壶。 */
        FILLED,
        /** 正下方是汤锅/茶壶，但源罐里是 Purity 0 的脏水：不抽源、不注水。 */
        DIRTY_WATER
    }

    /** {@link #tryFillBelow} 的结果：状态 + 实际抽出的流体（只有 {@code FILLED} 非空）。 */
    public record TapFill(TapFillStatus status, FluidStack fluid) {

        public static final TapFill NOT_APPLICABLE = new TapFill(TapFillStatus.NOT_APPLICABLE, FluidStack.EMPTY);
        public static final TapFill DIRTY_WATER = new TapFill(TapFillStatus.DIRTY_WATER, FluidStack.EMPTY);

        public boolean filled() {
            return this.status == TapFillStatus.FILLED;
        }

        public boolean rejectedDirtyWater() {
            return this.status == TapFillStatus.DIRTY_WATER;
        }
    }

    /**
     * 若 {@code targetPos} 为空且未盖盖子的汤锅或空水壶，且 {@code source} 可抽出 {@code 1000 mB} 原版水，
     * 则执行抽水并写入锅/壶状态；源水是 Purity 0 时返回 {@link TapFillStatus#DIRTY_WATER} 且不动源罐。
     */
    public static TapFill tryFillBelow(@Nullable Level level, BlockPos targetPos, IFluidHandler source) {
        if (level == null || level.isClientSide || !level.hasChunkAt(targetPos)) {
            return TapFill.NOT_APPLICABLE;
        }
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be == null || !ensureInitialized(be.getClass().getClassLoader())) {
            return TapFill.NOT_APPLICABLE;
        }
        try {
            if (stockpotClass.isInstance(be)) {
                boolean hasLid = (boolean) stockpotHasLid.invoke(be);
                int status = (int) stockpotGetStatus.invoke(be);
                if (!shouldFillStockpot(hasLid, status)) {
                    return TapFill.NOT_APPLICABLE;
                }
                if (isSourceWaterDirty(source)) {
                    return TapFill.DIRTY_WATER;
                }
                FluidStack drained = drainOneBucketOfWater(source);
                if (drained.isEmpty()) {
                    return TapFill.NOT_APPLICABLE;
                }
                stockpotSoupBaseId.set(be, waterSoupBaseId);
                stockpotSetStatus.invoke(be, 1);
                stockpotRefresh.invoke(be);
                return new TapFill(TapFillStatus.FILLED, drained);
            }
            if (teapotClass.isInstance(be)) {
                int status = (int) teapotGetStatus.invoke(be);
                ResourceLocation currentFluid = (ResourceLocation) teapotGetTeaFluidId.invoke(be);
                if (!shouldFillTeapot(status, emptyTeaFluidId.equals(currentFluid))) {
                    return TapFill.NOT_APPLICABLE;
                }
                if (isSourceWaterDirty(source)) {
                    return TapFill.DIRTY_WATER;
                }
                FluidStack drained = drainOneBucketOfWater(source);
                if (drained.isEmpty()) {
                    return TapFill.NOT_APPLICABLE;
                }
                ResourceLocation waterKey = BuiltInRegistries.FLUID.getKey(Fluids.WATER);
                teapotTeaFluidId.set(be, waterKey);
                teapotRefresh.invoke(be);
                return new TapFill(TapFillStatus.FILLED, drained);
            }
        } catch (ReflectiveOperationException ignored) {
            return TapFill.NOT_APPLICABLE;
        }
        return TapFill.NOT_APPLICABLE;
    }

    /** 汤锅未盖盖子且处于空锅状态（{@code status == 0}）时可注水。 */
    public static boolean shouldFillStockpot(boolean hasLid, int status) {
        return !hasLid && status == 0;
    }

    /** 茶壶/水壶处于初始状态（{@code status == 0}）且当前无液体（{@code EMPTY_TEA_FLUID}）时可注水。 */
    public static boolean shouldFillTeapot(int status, boolean currentlyEmptyFluid) {
        return status == 0 && currentlyEmptyFluid;
    }

    /** 源罐里待抽的那一桶水是否是脏水（只做 SIMULATE，不动源罐）。 */
    private static boolean isSourceWaterDirty(IFluidHandler source) {
        // 本类的 mixin 只按 fluid + kaleidoscope_cookery 门控；YAT 缺席时纯度链路整条不存在，
        // 这里必须先短路，否则 DirtyWaterPurity 的方法体会去解析 absent 的 YAT 类。
        if (!DirtyWaterCompat.purityAvailable()) {
            return false;
        }
        FluidStack probe = simulateOneBucketOfWater(source);
        return isFullWaterBucket(probe) && DirtyWaterPurity.isDirtyWaterFluid(probe);
    }

    /** 只探测、不抽取：优先按量取，取不到水型的 1000 mB 再显式请求 {@code minecraft:water}。 */
    private static FluidStack simulateOneBucketOfWater(IFluidHandler source) {
        FluidStack simByAmount = source.drain(FILL_AMOUNT_MB, IFluidHandler.FluidAction.SIMULATE);
        if (isFullWaterBucket(simByAmount)) {
            return simByAmount;
        }
        return source.drain(new FluidStack(Fluids.WATER, FILL_AMOUNT_MB), IFluidHandler.FluidAction.SIMULATE);
    }

    private static FluidStack drainOneBucketOfWater(IFluidHandler source) {
        FluidStack simulated = simulateOneBucketOfWater(source);
        if (!isFullWaterBucket(simulated)) {
            return FluidStack.EMPTY;
        }
        // 按探测到的 FluidStack 抽取：组件（纯度）随栈精确匹配，不会抽到别的变体。
        FluidStack drained = source.drain(simulated.copy(), IFluidHandler.FluidAction.EXECUTE);
        return isFullWaterBucket(drained) ? drained : FluidStack.EMPTY;
    }

    private static boolean isFullWaterBucket(FluidStack stack) {
        return !stack.isEmpty()
                && stack.getFluid() == Fluids.WATER
                && stack.getAmount() >= FILL_AMOUNT_MB;
    }

    private static boolean ensureInitialized(ClassLoader loader) {
        if (initialized) {
            return available;
        }
        synchronized (CookeryCopperTapSupport.class) {
            if (initialized) {
                return available;
            }
            try {
                stockpotClass = Class.forName(STOCKPOT_BE_CLASS, false, loader);
                Class<?> soupBasesClass = Class.forName(MOD_SOUP_BASES_CLASS, true, loader);
                stockpotHasLid = stockpotClass.getMethod("hasLid");
                stockpotGetStatus = stockpotClass.getMethod("getStatus");
                stockpotSetStatus = stockpotClass.getMethod("setStatus", int.class);
                stockpotRefresh = stockpotClass.getMethod("refresh");
                stockpotSoupBaseId = stockpotClass.getDeclaredField("soupBaseId");
                stockpotSoupBaseId.setAccessible(true);
                Field waterSoupField = soupBasesClass.getField("WATER");
                waterSoupBaseId = (ResourceLocation) waterSoupField.get(null);

                teapotClass = Class.forName(TEAPOT_BE_CLASS, false, loader);
                Class<?> serializerClass = Class.forName(TEAPOT_SERIALIZER_CLASS, true, loader);
                teapotGetStatus = teapotClass.getMethod("getStatus");
                teapotGetTeaFluidId = teapotClass.getMethod("getTeaFluidId");
                teapotRefresh = teapotClass.getMethod("refresh");
                teapotTeaFluidId = teapotClass.getDeclaredField("teaFluidId");
                teapotTeaFluidId.setAccessible(true);
                Field emptyTeaField = serializerClass.getField("EMPTY_TEA_FLUID");
                emptyTeaFluidId = (ResourceLocation) emptyTeaField.get(null);

                available = waterSoupBaseId != null && emptyTeaFluidId != null;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                available = false;
            } finally {
                initialized = true;
            }
            return available;
        }
    }

    private CookeryCopperTapSupport() {
    }
}

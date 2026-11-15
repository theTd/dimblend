package dimblend.experience.compat.fluid;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

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

    /**
     * 若 {@code targetPos} 为空且未盖盖子的汤锅或空水壶，且 {@code source} 可抽出 {@code 1000 mB} 原版水，
     * 则执行抽水并写入锅/壶状态，返回实际抽出的 {@link FluidStack}；否则返回 {@link FluidStack#EMPTY}。
     */
    public static FluidStack tryFillBelow(@Nullable Level level, BlockPos targetPos, IFluidHandler source) {
        if (level == null || level.isClientSide || !level.hasChunkAt(targetPos)) {
            return FluidStack.EMPTY;
        }
        BlockEntity be = level.getBlockEntity(targetPos);
        if (be == null || !ensureInitialized(be.getClass().getClassLoader())) {
            return FluidStack.EMPTY;
        }
        try {
            if (stockpotClass.isInstance(be)) {
                boolean hasLid = (boolean) stockpotHasLid.invoke(be);
                int status = (int) stockpotGetStatus.invoke(be);
                if (!shouldFillStockpot(hasLid, status)) {
                    return FluidStack.EMPTY;
                }
                FluidStack drained = drainOneBucketOfWater(source);
                if (drained.isEmpty()) {
                    return FluidStack.EMPTY;
                }
                stockpotSoupBaseId.set(be, waterSoupBaseId);
                stockpotSetStatus.invoke(be, 1);
                stockpotRefresh.invoke(be);
                return drained;
            }
            if (teapotClass.isInstance(be)) {
                int status = (int) teapotGetStatus.invoke(be);
                ResourceLocation currentFluid = (ResourceLocation) teapotGetTeaFluidId.invoke(be);
                if (!shouldFillTeapot(status, emptyTeaFluidId.equals(currentFluid))) {
                    return FluidStack.EMPTY;
                }
                FluidStack drained = drainOneBucketOfWater(source);
                if (drained.isEmpty()) {
                    return FluidStack.EMPTY;
                }
                ResourceLocation waterKey = BuiltInRegistries.FLUID.getKey(Fluids.WATER);
                teapotTeaFluidId.set(be, waterKey);
                teapotRefresh.invoke(be);
                return drained;
            }
        } catch (ReflectiveOperationException ignored) {
            return FluidStack.EMPTY;
        }
        return FluidStack.EMPTY;
    }

    /** 汤锅未盖盖子且处于空锅状态（{@code status == 0}）时可注水。 */
    public static boolean shouldFillStockpot(boolean hasLid, int status) {
        return !hasLid && status == 0;
    }

    /** 茶壶/水壶处于初始状态（{@code status == 0}）且当前无液体（{@code EMPTY_TEA_FLUID}）时可注水。 */
    public static boolean shouldFillTeapot(int status, boolean currentlyEmptyFluid) {
        return status == 0 && currentlyEmptyFluid;
    }

    private static FluidStack drainOneBucketOfWater(IFluidHandler source) {
        FluidStack simByAmount = source.drain(FILL_AMOUNT_MB, IFluidHandler.FluidAction.SIMULATE);
        if (isFullWaterBucket(simByAmount)) {
            FluidStack drained = source.drain(FILL_AMOUNT_MB, IFluidHandler.FluidAction.EXECUTE);
            return isFullWaterBucket(drained) ? drained : FluidStack.EMPTY;
        }
        FluidStack waterRequest = new FluidStack(Fluids.WATER, FILL_AMOUNT_MB);
        FluidStack simByStack = source.drain(waterRequest, IFluidHandler.FluidAction.SIMULATE);
        if (isFullWaterBucket(simByStack)) {
            FluidStack drained = source.drain(waterRequest, IFluidHandler.FluidAction.EXECUTE);
            return isFullWaterBucket(drained) ? drained : FluidStack.EMPTY;
        }
        return FluidStack.EMPTY;
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

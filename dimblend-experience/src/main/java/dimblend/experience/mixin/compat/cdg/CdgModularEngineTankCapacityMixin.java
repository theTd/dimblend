package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * B10 柴油机中号（组合式）燃料容量（2026-10-15 用户需求）：CDG 硬编码 1000mB →
 * 配置值 {@code dieselEngineFuelTankMb}（默认 100）。
 *
 * <p>常量面（CDG 1.3.15 javap 全类核实）：{@code ModularDieselEngineBlockEntity} 全类仅
 * 两处 1000 = 构造器 offset 24（{@code new SmartFluidTank(1000, consumer)}）与
 * {@code read(...)} 连通性变化 isController 分支 offset 278（offset 268
 * {@code isController()} → 278 {@code sipush 1000} → 281
 * {@code FluidTank.setCapacity(I)}），两处按方法限定 @ModifyConstant 精确替换、
 * 无误伤面。CDG 配置文件无油箱容量项（TrainTripWorld/config 三个
 * createdieselgenerators-*.toml 已查），只能 mixin。</p>
 *
 * <p>生效机制（javap offset 201-205 核实：{@code iload_3; ifne 206; return}——
 * clientPacket=false 即服务端磁盘读档直接 return，走不到 setCapacity 分支）：<b>服务端
 * 容量由构造器定型</b>（新放置/重进世界 BE 重建即生效）；read 重断言的真实生效面是
 * <b>客户端同步包路径</b>（read(clientPacket=true) 且连通性/长度变化时到达）——防客户端
 * 容量被重断言回 1000 与服务端分叉。存量兼容（NeoForge 21.1.249 FluidTank javap 核实）：
 * setCapacity 只写 capacity 字段不截断存油、fill 按 容量−存量 计空间——超出新容量的
 * 存油保留烧完为止、不能再补到容量以上。配置未加载（标题画面等无 SERVER 配置场景）
 * 透传原生 1000。</p>
 *
 * <p>两处注入 require=1（mixins.json defaultRequire 钉死）：CDG 若改常量面/方法签名
 * （含仅客户端路径的 read 重断言——GameTest 服务端环境覆盖不到该路径，更依赖启动期
 * 注入钉死），启动期 InjectionError 即响；运行期容量由 CdgTankCapacityGameTests 实测
 * 钉死。普通（小号）容量由 {@code CdgEngineTankCapacityMixin} 覆盖。</p>
 */
@Mixin(ModularDieselEngineBlockEntity.class)
public abstract class CdgModularEngineTankCapacityMixin {

    /** 组合式（中号）：构造器 {@code new SmartFluidTank(1000, consumer)} 的容量实参（服务端定型路径）。 */
    @ModifyConstant(
            method = "<init>(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
            constant = @Constant(intValue = 1000))
    private int dimblend$modularTankCapacity(int original) {
        return Config.isLoaded() ? Config.DIESEL_ENGINE_FUEL_TANK_MB.get() : original;
    }

    /**
     * 组合式（中号）：{@code read(Lnet/minecraft/nbt/CompoundTag;
     * Lnet/minecraft/core/HolderLookup$Provider;Z)V} 连通性变化 isController 分支的
     * setCapacity 实参——客户端同步包（clientPacket=true）路径的容量重断言，
     * 防客户端容量回 1000 与服务端分叉。
     */
    @ModifyConstant(
            method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            constant = @Constant(intValue = 1000))
    private int dimblend$modularTankReassert(int original) {
        return Config.isLoaded() ? Config.DIESEL_ENGINE_FUEL_TANK_MB.get() : original;
    }
}

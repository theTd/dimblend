package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * B10 柴油机小号（普通）燃料容量（2026-10-15 用户需求）：CDG 硬编码 1000mB →
 * 配置值 {@code dieselEngineFuelTankMb}（默认 100）。
 *
 * <p>常量面（CDG 1.3.15 javap 全类核实）：{@code DieselEngineBlockEntity} 全类唯一
 * 1000 = {@code addBehaviours} offset 46 {@code sipush 1000}
 * （{@code SmartFluidTankBehaviour.single(this, 1000)} 的容量实参），按方法限定
 * @ModifyConstant 精确替换、无误伤面。CDG 配置文件无油箱容量项（TrainTripWorld/config
 * 三个 createdieselgenerators-*.toml 已查），只能 mixin。</p>
 *
 * <p>生效时机：普通引擎油箱在 BE 初始化（addBehaviours）创建即定型，双端同一构造
 * 路径——新放置/区块重进生效，服务端与客户端容量一致。存量兼容（NeoForge 21.1.249
 * FluidTank javap 核实）：setCapacity 只写 capacity 字段不截断存油、fill 按 容量−存量
 * 计空间——超出新容量的存油保留烧完为止、不能再补到容量以上。配置未加载（标题画面等
 * 无 SERVER 配置场景）透传原生 1000。</p>
 *
 * <p>注入 require=1（mixins.json defaultRequire 钉死）：CDG 若改常量面/方法签名，
 * 启动期 InjectionError 即响，不静默失效；运行期容量由 CdgTankCapacityGameTests 实测
 * 钉死。组合式（中号）容量由 {@code CdgModularEngineTankCapacityMixin} 覆盖，
 * 巨型原生 100mB 不动（本需求不覆盖）。</p>
 */
@Mixin(DieselEngineBlockEntity.class)
public abstract class CdgEngineTankCapacityMixin {

    /** 普通（小号）：{@code addBehaviours} 内 {@code SmartFluidTankBehaviour.single} 的容量实参。 */
    @ModifyConstant(
            method = "addBehaviours(Ljava/util/List;)V",
            constant = @Constant(intValue = 1000))
    private int dimblend$normalTankCapacity(int original) {
        return Config.isLoaded() ? Config.DIESEL_ENGINE_FUEL_TANK_MB.get() : original;
    }
}

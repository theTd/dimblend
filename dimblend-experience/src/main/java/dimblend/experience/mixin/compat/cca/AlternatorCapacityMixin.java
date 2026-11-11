package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.alternator.AlternatorBlockEntity;
import com.mrh0.createaddition.energy.InternalEnergyStorage;
import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * D9 交流发电机内部储能上限（2026-11-11 用户需求：500K FE）。
 *
 * <p>字节码基线（运行 jar createaddition 1.5.10，javap -c 核实）：
 * {@code AlternatorBlockEntity.<init>(BlockEntityType, BlockPos, BlockState)} 内
 * offset 35-64 为
 * {@code new InternalEnergyStorage(CommonConfig.ALTERNATOR_CAPACITY.get(),
 * 0, CommonConfig.ALTERNATOR_MAX_OUTPUT.get())}，随后 offset 67 putfield energy。
 * CCA 的容量来自自己的 common 配置 {@code generator_capacity}（默认 5000），
 * 整合包 toml 里同样是 5000，且该配置项没有"按方块区分"的维度——直接改 toml 会同时
 * 影响所有读取它的路径。本 mixin 只在发电机的构造返回后把 <b>本实例</b> 的
 * capacity 抬到 {@code alternatorCapacityFe}（默认 500000），不碰 CCA 配置、
 * 不碰 {@code ALTERNATOR_MAX_OUTPUT}（最大输出仍是 5000 FE/t，属独立需求）。</p>
 *
 * <p>生效面：容量在 BE 构造器定型——新放置 / 区块重进（BE 重建）生效。
 * {@code read(CompoundTag, Provider, boolean)} 只回填存量（
 * {@code energy.read(tag)} → {@code setEnergy}），<b>不重设容量</b>，所以读档不会
 * 把容量打回 5000。同一处 read 在 TAIL 里把存量钳到容量：若玩家把配置下调到低于
 * 存量，下一 tick 不会出现"存量 &gt; 上限"的异常读数（NeoForge 21.1.249
 * {@code EnergyStorage} 的 extract 只看 energy 字段，钳定只影响显示与
 * {@code getSpace()}）。配置未加载（标题画面等无 SERVER 配置场景）透传 CCA 原值。</p>
 *
 * <p>注入 {@code require=1}（mixins.json defaultRequire 钉死）：CCA 若改构造器签名或
 * 改 InternalEnergyStorage 的持有方式，启动期 InjectionError 即响，不静默失效。
 * 与 {@code AlternatorIdleDrainMixin} 同目标类，两者互不重叠（一个改容量、一个改 tick 扣减）。</p>
 */
@Mixin(AlternatorBlockEntity.class)
public abstract class AlternatorCapacityMixin {

    /**
     * 目标为 protected final（构造器初始化）。按 AGENTS.md 的 mixin 纪律，shadow 成员本身
     * 一律 private —— shadow 声明只是编译期占位，apply 期会被剥离并改写为对目标字段的访问，
     * 因此这里的可见性不影响对目标 protected 字段的读写；本 mixin 只读该实例以调用其 setter。
     */
    @Shadow(remap = false)
    private InternalEnergyStorage energy;

    /** 构造返回后抬上限：容量定型，后续 read 不重设容量。 */
    @Inject(
            method = "<init>(Lnet/minecraft/world/level/block/entity/BlockEntityType;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("RETURN"))
    private void dimblend$applyConfiguredCapacity(CallbackInfo ci) {
        if (!Config.isLoaded()) {
            return;
        }
        this.energy.setCapacity(Config.ALTERNATOR_CAPACITY_FE.get());
    }

    /** 读档只回填存量，这里把存量钳到容量（配置下调后的存量兼容）。 */
    @Inject(
            method = "read(Lnet/minecraft/nbt/CompoundTag;"
                    + "Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"))
    private void dimblend$clampStoredEnergyToCapacity(CallbackInfo ci) {
        if (!Config.isLoaded()) {
            return;
        }
        int capacity = Config.ALTERNATOR_CAPACITY_FE.get();
        int stored = this.energy.getEnergyStored();
        if (stored > capacity) {
            this.energy.setEnergy(capacity);
        }
    }
}

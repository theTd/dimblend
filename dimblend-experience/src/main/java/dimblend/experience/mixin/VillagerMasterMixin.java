package dimblend.experience.mixin;

import dimblend.experience.Config;
import dimblend.experience.exploration.ExplorationAttachments;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * G1 村民大师守卫（原版 {@code Villager} 目标，无条件应用）：
 * <ul>
 * <li>补货拦截：已收编村民的 {@code restock()} 直接取消——单次交易用完即永久锁死，
 * 需求/价格也不再浮动。</li>
 * <li>掉职业拦截：已收编村民的 {@code releasePoi(JOB_SITE)} 直接取消——职业方块
 * 丢失后职业与工作站点记忆一并保留（村民 AI 对失效站点自适应，无事）。</li>
 * </ul>
 * <p>两处仅对收编村民（{@code VILLAGER_PROFESSION} 非空）+ rotating 服务端 +
 * 开关开启时生效，其余一律透传原版。</p>
 */
@Mixin(Villager.class)
public abstract class VillagerMasterMixin {

    @Inject(method = "restock()V", at = @At("HEAD"), cancellable = true)
    private void dimblend$blockRestock(CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (!Config.VILLAGER_MASTER.get()) {
            return;
        }
        if (!(self.level() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (self.getData(ExplorationAttachments.VILLAGER_PROFESSION.get()).isEmpty()) {
            return;
        }
        ci.cancel();
    }

    @Inject(method = "releasePoi(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;)V",
            at = @At("HEAD"), cancellable = true)
    private void dimblend$keepProfession(MemoryModuleType<?> type, CallbackInfo ci) {
        if (type != MemoryModuleType.JOB_SITE) {
            return;
        }
        Villager self = (Villager) (Object) this;
        if (!Config.VILLAGER_MASTER.get()) {
            return;
        }
        if (!(self.level() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (self.getData(ExplorationAttachments.VILLAGER_PROFESSION.get()).isEmpty()) {
            return;
        }
        ci.cancel();
    }

    /**
     * G1 新鲜戳：任何职业获得（转职/治愈/自然认领，NONE 丢失除外）即打戳，
     * 扫描只收有戳者——老存档村民天然无戳，永不追溯。只看职业变化，
     * 定级（setLevel）不打戳；开关关闭时不打戳（关闭期即原版）。
     * 我方恢复路径的 setVillagerData 同样打戳，但已收编村民的戳会被扫描顺手消费，
     * 无影响。
     */
    @Inject(method = "setVillagerData(Lnet/minecraft/world/entity/npc/VillagerData;)V",
            at = @At("HEAD"))
    private void dimblend$stampFresh(VillagerData data, CallbackInfo ci) {
        if (data.getProfession() == net.minecraft.world.entity.npc.VillagerProfession.NONE) {
            return;
        }
        Villager self = (Villager) (Object) this;
        if (self.getVillagerData().getProfession() == data.getProfession()) {
            return;
        }
        if (!Config.VILLAGER_MASTER.get()) {
            return;
        }
        if (!(self.level() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        self.setData(ExplorationAttachments.VILLAGER_FRESH.get(), true);
    }
}

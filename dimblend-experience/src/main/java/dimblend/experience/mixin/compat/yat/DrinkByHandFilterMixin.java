package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.compat.yat.InventoryFilterDrinking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 手捧水（蹲下右键水源，YAT {@code DrinkByHandPacket}）的两件事：滤芯过滤 + Sable 距离投影。
 *
 * <p>目标类 {@code NeoForgeNetwork$DrinkByHandPacket} 是包私有 record，编译期拿不到
 * 类字面量，故用 {@code targets} 字符串；真实逻辑在私有静态 lambda
 * {@code lambda$handle$0}（{@code handle} 只是 {@code enqueueWork} 包一层）。</p>
 *
 * <p><b>① 滤芯过滤</b>：直接包 {@code WaterPurity.givePurityEffects(entity, purity)} 这一次调用——
 * {@code purity == 0} 且背包里有干净滤芯时改成 {@code 1}，于是毒副作用与 {@code DIRTY_WATER} 进度
 * 都不触发，而水本身照常喝下去。</p>
 *
 * <p><b>② Sable 距离投影</b>（推翻 {@code manifest-s5.md} §10.3）：原版在
 * {@code player.getEyePosition().distanceToSqr(pos.getX()+0.5, ...) > 36} 时静默丢弃这次手捧水。
 * 字节码（{@code javap -p -c}）：</p>
 * <ul>
 *   <li>客户端 {@code NeoForgeHandDrinkClient#tryDrink} 用 {@code Minecraft.level.clip(...)}
 *       的 {@code BlockHitResult#getBlockPos()} 当 {@code packet.pos}。Sable 覆盖了
 *       {@code BlockGetter#clip}（{@code sable/common/.../clip_overwrite/BlockGetterMixin}）：
 *       对子层级是把世界射线<b>反向投进 plot 空间</b>、在 {@code subLevel.getLevel()} 里做
 *       {@code originalClip}，所以命中点是 <b>plot 坐标</b>（{@code X >= 20,480,000}）；</li>
 *   <li>服务端玩家实体记的是<b>世界坐标</b>：{@code sable/.../entity/ServerEntityMixin} 只在
 *       {@code ServerEntity#sendChanges} 发包期间临时把 {@code pos} 字段换成 plot 坐标，
 *       发完立刻还原（{@code sable$postTransform}）。</li>
 * </ul>
 * <p>于是 {@code worldEye.distanceToSqr(plotPos)} 恒为天文数字，船上/车内蹲下喝水的包<b>100% 被丢</b>。
 * 修法：两点都过 {@link SableWorldPosition#project}（plot → 世界；世界坐标幂等，
 * {@code getContaining} 为空就原样返回），陆地上数值完全不变。</p>
 *
 * <p>玩家对象的传递用 MixinExtras {@code @Share}（与 {@code sable} 自己的
 * {@code ServerEntityMixin} 同型）：距离检查在 {@code IPayloadContext#player()} 之后执行，
 * 所以在 {@code player()} 这一点把玩家塞进共享槽，到 {@code distanceToSqr} 再取出来算
 * {@code level}。两处都钉 {@code require = 1, allow = 1}。</p>
 */
@Pseudo
@Mixin(targets = "dev.minhnh.yetanotherthirst.NeoForgeNetwork$DrinkByHandPacket", remap = false)
public abstract class DrinkByHandFilterMixin {

    @WrapOperation(method = "lambda$handle$0",
            at = @At(value = "INVOKE",
                    target = "Ldev/minhnh/yetanotherthirst/core/purity/WaterPurity;givePurityEffects(Lnet/minecraft/world/entity/LivingEntity;I)Z"),
            remap = false, require = 1, allow = 1)
    private static boolean dimblend$filterHandDrinking(LivingEntity entity, int purity, Operation<Boolean> original) {
        int filtered = purity;
        if (purity <= WaterPurity.MIN_PURITY
                && entity instanceof ServerPlayer player
                && DirtyWaterCompat.purityAvailable()
                && InventoryFilterDrinking.tryFilterDirtyDrink(player)) {
            filtered = InventoryFilterDrinking.filteredPurity();
        }
        return original.call(entity, filtered);
    }

    /** 先把玩家截下来，供下一点算 {@code level()}。 */
    @WrapOperation(method = "lambda$handle$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/network/handling/IPayloadContext;player()Lnet/minecraft/world/entity/player/Player;"),
            remap = false, require = 1, allow = 1)
    private static Player dimblend$capturePlayer(IPayloadContext context, Operation<Player> original,
            @Share("dimblend$player") LocalRef<Player> shared) {
        Player player = original.call(context);
        shared.set(player);
        return player;
    }

    /** 距离检查：把 {plot 命中点} 与 {世界眼睛} 统一到世界坐标再比。 */
    @WrapOperation(method = "lambda$handle$0",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/Vec3;distanceToSqr(DDD)D"),
            remap = false, require = 1, allow = 1)
    private static double dimblend$projectSableDistance(Vec3 eye, double x, double y, double z,
            Operation<Double> original, @Share("dimblend$player") LocalRef<Player> shared) {
        Player player = shared.get();
        Level level = player == null ? null : player.level();
        if (level == null) {
            return original.call(eye, x, y, z);
        }
        Vec3 target = SableWorldPosition.project(level, new Vec3(x, y, z));
        return SableWorldPosition.project(level, eye).distanceToSqr(target);
    }
}

package dimblend.experience.mixin.compat.firefightingadd;

import com.mikoalopex.createfirefightingadd.content.blocks.fire_hose.FireHoseBlockEntity;
import com.mikoalopex.createfirefightingadd.content.blocks.fire_hose.PlaceFireHosePacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 消防水带「放置/拆除数量不守恒」修复（2026-10-20 用户报告）——补上第二个端点的
 * 物品消耗，使每个水带方块 = 1 个水带物品（进=出）。
 *
 * <p>证据（createfirefightingadd 0.2.1-beta，javap 核实）：客户端
 * {@code FireHoseItemHandler#onUse} 第二次点击地面时发 {@code PLACE_AND_CONNECT_FREE}
 * （{@code consumeItem = !placedEndpointThisSession}，即本会话刚放过端点就当「同一条
 * 水带的另一头」免单）；服务端 {@code PlaceFireHosePacket#handle} 按 action 分派到
 * 私有静态 {@code placeEndpoint(Level, ServerPlayer, ItemStack, BlockPos, Direction,
 * boolean)}。该方法 bytecode offset 78-95 才是扣费分支：
 * {@code iload 5; ifeq 95; …hasInfiniteMaterials(); …shrink(1)}——{@code consumeItem}
 * 为 false 时**一个新水带方块白放**。于是「拿 1 个水带放出一对端点」= 1 进 2 出，
 * 与本次修复掉的「每方块掉 2 个」（见 {@code FireHoseExtraDropMixin}）叠加成用户
 * 观测到的 4 倍。</p>
 *
 * <p>修法：在 offset 75 的 {@code ServerPlayer#awardStat(Stat)}（放置成功后的第一句，
 * 晚于两次 {@code addHose} 失败早退）之后注入——能走到这里就证明方块已放下、玩家手持
 * 的 {@code hose} 就是本 packet 的消耗目标；{@code consumeItem} 为 false 且非创造模式
 * 时由本注入补扣 1 个，为 true 时仍走上游自己的扣费分支（不重复扣）。取
 * {@code awardStat} 后而非方法返回前，是为了避免依赖返回值捕获/局部变量表，
 * 参数全部来自方法签名。</p>
 *
 * <p>注入钉死 {@code require = 1, allow = 1}，目标方改动该方法签名即装包期报错。
 * 回调必须是 {@code private static}：目标方法为静态，Mixin 明确拒绝非静态回调
 * （{@code InvalidInjectionException: non-static callback method ... targets a static
 * method}，本机 runGameTestServer 实测）；private 则只随 mixin 合并、不额外暴露。</p>
 */
@Mixin(PlaceFireHosePacket.class)
public abstract class FireHosePlacementCostMixin {

    @Inject(
            method = "placeEndpoint("
                    + "Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/core/Direction;"
                    + "Z)"
                    + "Lcom/mikoalopex/createfirefightingadd/content/blocks/fire_hose/FireHoseBlockEntity;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/stats/Stat;)V",
                    shift = At.Shift.AFTER),
            require = 1,
            allow = 1)
    private static void dimblend$chargeFreeEndpoint(Level level, ServerPlayer player, ItemStack hose,
            BlockPos clickedPos, Direction facing, boolean consumeItem, CallbackInfoReturnable<FireHoseBlockEntity> cir) {
        if (consumeItem || player.hasInfiniteMaterials()) {
            return;
        }
        // 免费端点：与上游扣费分支同一判据（创造模式不扣），每个方块恒定消耗 1 个水带
        hose.shrink(1);
    }
}

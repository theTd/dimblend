package dimblend.experience.mixin.compat.firefightingadd;

import com.mikoalopex.createfirefightingadd.content.blocks.fire_hose.FireHoseBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 消防水带「拆一次掉 4 个」修复（2026-10-20 用户报告）——去掉与战利品表并行的第二份掉落。
 *
 * <p>证据（createfirefightingadd 0.2.1-beta，javap 核实）：{@code FireHoseBlockEntity}
 * 继承 Create 的 {@code SmartBlockEntity}，而 {@code SmartBlockEntity#setRemoved()}
 * 在非区块卸载时会回调 {@code remove()}（Create 6.0.10 {@code SmartBlockEntity}
 * offset 123-129）。本模组覆写的 {@code remove()} 在末尾（offset 97-121）无条件
 * {@code Block.popResource(level, worldPosition, new ItemStack(FIRE_HOSE_ITEM))}。
 * 方块本体另有战利品表 {@code data/createfirefightingadd/loot_table/blocks/fire_hose.json}
 * （rolls=1、单条 item 掉落、仅 survives_explosion 条件），于是**每次**移除都掉两份：
 * 战利品表 1 份 + 方块实体自弹 1 份。生存挖掘、扳手拆除（{@code IWrenchable#onSneakWrenched}
 * 先把 {@code Block.getDrops} 塞进背包、再 {@code destroyBlock(pos, false)}）、爆炸
 * （无玩家上下文）全部命中该 popResource；创造模式破坏原版不滚战利品表，但这行照弹，
 * 额外多出一条「创造模式拆水带地上冒一个水带」的复制路径。</p>
 *
 * <p>修法取最小面：把这次 {@code popResource} 调用取消，物品归还完全交回战利品表——
 * 生存挖掘/爆炸仍是 1 个，扳手仍是「1 个进背包」，创造模式 0 个，与其它 Create 机器
 * 一致。{@code remove()} 前半段的断开伙伴/移动端点清理照常执行（注入点在方法的最后
 * 一条语句，取消后直接进入 return，语义等价于跳过这次掉落）。</p>
 *
 * <p>注入钉死 {@code require = 1, allow = 1}：目标方若改动 {@code remove()} 或掉落实现，
 * 装包期 {@code InjectionError} 立即报错，不静默失效。</p>
 */
@Mixin(FireHoseBlockEntity.class)
public abstract class FireHoseExtraDropMixin {

    @Inject(
            method = "remove()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;popResource("
                            + "Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/item/ItemStack;)V"),
            cancellable = true,
            require = 1,
            allow = 1)
    private void dimblend$skipExtraHoseDrop(CallbackInfo ci) {
        // 掉落已由 fire_hose 战利品表给出，这里不再弹第二份
        ci.cancel();
    }
}

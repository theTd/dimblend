package dimblend.experience.mixin.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import dimblend.experience.compat.cdg.CdgAttachments;
import dimblend.experience.compat.cdg.CdgEngineDamage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 掉落物带上"这台机器活塞是坏的"标记（2026-11-25 防洗损坏口径）。
 *
 * <p>三机都不声明 {@code Block#playerDestroy}（{@code javap -p} 已核），所以这里可以
 * 直接 merge 一个覆写：行为与原版 {@link Block#playerDestroy}（inside: awardStat →
 * causeFoodExhaustion → dropResources）等价，唯一差别是掉落的引擎本体那一件打上
 * {@link CdgEngineDamage#TAG_PISTON_DAMAGED}，放回去时由
 * {@code CdgPistonDamageRestoreMixin} 还原损坏态。</p>
 *
 * <p>非损坏状态下打标分支不执行，掉落与拿在手里的完全一样。</p>
 */
@Mixin({DieselEngineBlock.class, ModularDieselEngineBlock.class, HugeDieselEngineBlock.class})
public abstract class CdgPistonDamageDropMixin {

    /**
     * 与 {@code Block#playerDestroy} 同签名（原版方法体逐条复刻）。
     */
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
            BlockEntity blockEntity, ItemStack tool) {
        player.awardStat(Stats.BLOCK_MINED.get((Block) (Object) this));
        player.causeFoodExhaustion(0.005F);
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean damaged = blockEntity instanceof IEngine engine
                && engine.self().getData(CdgAttachments.ENGINE_STATE).pistonDamaged;
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, player, tool);
        for (ItemStack drop : drops) {
            if (damaged) {
                CdgEngineDamage.tagDamagedDrop(drop);
            }
            Block.popResource(serverLevel, pos, drop);
        }
        state.spawnAfterBreak(serverLevel, pos, tool, true);
    }
}

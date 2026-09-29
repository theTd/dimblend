package dimblend.blocks.compat.create;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 创造传动件放置兜底：非创造玩家（及一切非玩家来源）放置六个创造变体一律取消。
 *
 * <p>背景：齿轮物品的 helper 连放（onItemUseFirst → placeInWorld）与轴的极柱
 * helper 均不经 canPlace/getStateForPlacement（字节码核实），物品侧创造门之外的
 * 一切旁路（含其他 mod 的放置手段）都由本守卫兜住。catnip 的
 * {@code playerPlaceSingleBlock} 会照常抛出 EntityPlaceEvent 且取消后回滚快照，
 * 本守卫因此能覆盖 helper 路径。依赖只出现在方法体内 + isLoaded 守卫先行，
 * Create 未加载时直接返回，不解析任何依赖类。</p>
 *
 * <p><b>判别「放置」与「互转/拆壳」</b>：NeoForge 在服务端 {@code ItemStack.useOn}
 * （{@code CommonHooks.onPlaceItemIntoWorld}）里会对物品 use 期间的全部 setBlock
 * 补拍快照并补抛 EntityPlaceEvent——Create 扳手的 {@code switchToBlockState}
 * （裸块互转、潜行拆壳，同格替换自家方块）因此也会落到本事件。它们是设计允许的
 * 生存操作，必须放行：快照里的<b>旧状态</b>是本板块方块时说明是同格转换而非放置，
 * 不拦；旧状态是空气/他物的才是真正的新放置（GameTest
 * {@code CreativeKineticsGameTest} 钉死：误拦时 onPlaceItemIntoWorld 回滚并返回 FAIL，
 * 表现为生存扳手互转无效）。</p>
 */
public final class CreativeKineticGuard {

    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModList.get().isLoaded("create")) {
            return;
        }
        Entity placer = event.getEntity();
        if (placer instanceof Player player && player.isCreative()) {
            return;
        }
        if (!isCreativeKinetic(event.getPlacedBlock().getBlock())) {
            return;
        }
        // 同格替换自家方块（扳手互转/潜行拆壳的 switchToBlockState）不是放置，放行
        if (isCreativeKinetic(event.getBlockSnapshot().getState().getBlock())) {
            return;
        }
        event.setCanceled(true);
    }

    private static boolean isCreativeKinetic(Block block) {
        return block == CreativeKinetics.CREATIVE_SHAFT.get()
                || block == CreativeKinetics.CREATIVE_COGWHEEL.get()
                || block == CreativeKinetics.CREATIVE_ANDESITE_ENCASED_SHAFT.get()
                || block == CreativeKinetics.CREATIVE_BRASS_ENCASED_SHAFT.get()
                || block == CreativeKinetics.CREATIVE_ANDESITE_ENCASED_COGWHEEL.get()
                || block == CreativeKinetics.CREATIVE_BRASS_ENCASED_COGWHEEL.get();
    }

    private CreativeKineticGuard() {
    }
}

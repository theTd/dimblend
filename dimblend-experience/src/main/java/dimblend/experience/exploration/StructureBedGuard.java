package dimblend.experience.exploration;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.BedBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * G2 只有 sable 结构上的床可交互（服务端右键拦截）：
 * rotating 内对床方块的右键使用——床自身不在 sable 子层级内一律取消
 * （不睡、不设重生点、不爆炸）。只拦使用不拦破坏（左键/挖掘走原版）；
 * 创造模式豁免；村民 AI 睡觉（非右键路径）不管。
 *
 * <p>sable 缺席时守卫直接放行（fail-open，避免无结构维度睡不了觉）。
 * 对 {@code Sable.HELPER} 的引用只在守卫通过后触达，JVM 惰性解析，
 * 缺失时不加载本类——与 {@code SimurailBlocksHolder} 同型。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class StructureBedGuard {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !RotatingDimension.is(level)) {
            return;
        }
        if (!Config.STRUCTURE_BED.get()) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BedBlock)) {
            return;
        }
        Player player = event.getEntity();
        if (player.isCreative()) {
            return;
        }
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        if (Sable.HELPER.getContaining(level, (Vec3i) event.getPos()) == null) {
            event.setCanceled(true);
        }
    }

    private StructureBedGuard() {
    }
}

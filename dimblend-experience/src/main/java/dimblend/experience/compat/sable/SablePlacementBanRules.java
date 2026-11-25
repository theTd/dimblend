package dimblend.experience.compat.sable;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Sable 载具上的「地面机器」放置禁令（2026-11-25 用户口径）。
 *
 * <p>风车轴承 / 水车 / 大水车 / 星辉水晶胎胚这类需要站在地面上的产能或生长机器，
 * 一旦随载具移动，其流体/作物判定会读到 plot 局部坐标并产生伪产能或伪生长。
 * 因此它们只允许放在真实世界地面上：只要目标位置落在任一 Sable 子层级内就拒绝放置。
 *
 * <p>判定只按 <b>方块 id</b> 做，不解析任何第三方类型：星辉缺席时该 id 永远匹配不上，
 * 所以规则类可以无条件加载；对 Sable 的引用放在惰性内部持有类里，Sable 缺席
 * （理论上不会发生，载具本就来自 Sable）也不会在类初始化阶段炸掉。</p>
 */
public final class SablePlacementBanRules {

    /** 禁止放在 Sable 载具上的方块。 */
    public static final Set<ResourceLocation> BANNED_ON_SUBLEVEL = Set.of(
            ResourceLocation.fromNamespaceAndPath("eternal_starlight", "crystalborn_catalyst"),
            ResourceLocation.fromNamespaceAndPath("create", "windmill_bearing"),
            ResourceLocation.fromNamespaceAndPath("create", "water_wheel"),
            ResourceLocation.fromNamespaceAndPath("create", "large_water_wheel"));

    /** 纯判定：该方块是否属于禁令名单（供单测钉死名单本身）。 */
    public static boolean isBannedBlock(ResourceLocation blockId) {
        return blockId != null && BANNED_ON_SUBLEVEL.contains(blockId);
    }

    /**
     * 放置前判定。返回 true = 拒绝（调用方按失败处理，不消耗物品）。
     *
     * @param stack 被放置的物品（非 BlockItem 一律放行，由调用方先行过滤）
     */
    public static boolean shouldBlockPlacement(ItemStack stack, Level level, BlockPos pos) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return shouldBlockPlacement(blockItem.getBlock().builtInRegistryHolder().key().location(), level, pos);
    }

    public static boolean shouldBlockPlacement(ResourceLocation blockId, Level level, BlockPos pos) {
        if (!isBannedBlock(blockId)) {
            return false;
        }
        return isInsideSubLevel(level, pos);
    }

    private static boolean isInsideSubLevel(Level level, BlockPos pos) {
        if (level == null || pos == null || !ModList.get().isLoaded("sable")) {
            return false;
        }
        return SableAccess.isInsideSubLevel(level, pos);
    }

    /** Only resolved once the sable gate has passed, so the type never loads without Sable. */
    private static final class SableAccess {
        private static boolean isInsideSubLevel(Level level, BlockPos pos) {
            return dev.ryanhcode.sable.Sable.HELPER.getContaining(level, pos) != null;
        }
    }

    private SablePlacementBanRules() {
    }
}

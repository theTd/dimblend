package dimblend.experience.compat.create.vault;

import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 一次读数：把"显示连接器读到的那个保险库方块"折成整台多结构的槽位统计。
 *
 * <p>读取路径（对 物品保险库 / 纵向物品保险库 / 集装箱 通用，不引用任何 addon 类型）：</p>
 * <ol>
 *   <li>源方块实体实现 Create 的 {@link IMultiBlockEntityContainer}（三种变体都实现了
 *       {@code IMultiBlockEntityContainer.Inventory}），由 {@code getController()} 拿到主方块，
 *       因此贴在任意一段（含非主控段）都能读到整台结构。</li>
 *   <li>主方块按 {@code getChunkSource().getChunkNow(...)} 取：绝不在显示连接器的刷新路径上
 *       触发同步区块加载（dimblend-dev §8.28）。整台多结构（半径/长度）覆盖到的区块也必须全部
 *       已是 {@code FULL}，否则本次返回 {@code null}，等区块就位后自然重算。</li>
 *   <li>物品访问走 {@code Capabilities.ItemHandler.BLOCK}：Create 在主方块上挂的是
 *       {@code VersionedInventoryWrapper(SameSizeCombinedInvWrapper)}，即整台结构拼接后的库存；
 *       纵向保险库/集装箱是 {@code CombinedInvWrapper}，{@code getSlots()}/{@code getStackInSlot()}
 *       口径相同。capability 用已解析出的主方块 + 状态直接查询，绕过 {@code level.getBlockEntity}。</li>
 * </ol>
 *
 * <p>已知边界：Create 的拼接库在结构被部分卸载时会把缺失段替换成空 {@code ItemStackHandler}
 * （1 格），并且缓存到 {@code notifyMultiUpdated} 为止；这是 Create 自身行为，读数与漏斗/打包机
 * 完全同源。上面的区块守卫让常规路径不会撞上它。</p>
 */
public final class VaultInventoryReader {

    /**
     * @param level     源所在维度
     * @param sourcePos 显示连接器指向的源方块坐标（全程走 {@code getChunkNow}，不触发同步区块加载）
     * @return 整台结构的槽位统计；源区块未加载、不是保险库、主方块/远角区块未加载或无物品 capability 时返回 null
     */
    @Nullable
    public static VaultStorageMath.Tally tally(Level level, @Nullable BlockPos sourcePos) {
        if (level == null || sourcePos == null) {
            return null;
        }
        return tally(level, blockEntityNow(level, sourcePos));
    }

    /**
     * @param level   源所在维度
     * @param source  显示连接器读到的那一段保险库方块实体
     * @return 整台结构的槽位统计；不是保险库、主方块/区块不可用、或没有物品 capability 时返回 null
     */
    @Nullable
    public static VaultStorageMath.Tally tally(Level level, @Nullable BlockEntity source) {
        if (level == null || source == null || !(source instanceof IMultiBlockEntityContainer multi)) {
            return null;
        }
        BlockPos controllerPos = multi.getController();
        if (controllerPos == null) {
            return null;
        }
        BlockEntity controller = blockEntityNow(level, controllerPos);
        if (controller == null || !multiblockLoaded(level, controllerPos, controller)) {
            return null;
        }
        IItemHandler handler = Capabilities.ItemHandler.BLOCK.getCapability(
                level, controllerPos, controller.getBlockState(), controller, null);
        if (handler == null) {
            return null;
        }

        VaultStorageMath.Tally tally = new VaultStorageMath.Tally();
        int slots = handler.getSlots();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                tally.addSlot(0, 1);
            } else {
                tally.addSlot(stack.getCount(), stack.getMaxStackSize());
            }
        }
        return tally;
    }

    /** 非阻塞取方块实体：区块不在（或不是 {@code FULL}）就返回 null，绝不加载。 */
    @Nullable
    private static BlockEntity blockEntityNow(Level level, BlockPos pos) {
        LevelChunk chunk = chunkNow(level, pos);
        return chunk == null ? null : chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
    }

    @Nullable
    private static LevelChunk chunkNow(Level level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /**
     * 整台多结构覆盖的区块是否都已加载。Create 的保险库与 create_connected 的纵向保险库一律以主方块为最小角、
     * 沿正方向展开（{@code ItemVaultBlockEntity#initCapability} / {@code ItemSiloBlockEntity#initCapability}）：
     * 主轴为 X 时沿 +X 延伸 {@code height}、+Z 延伸 {@code width}；主轴为 Z 时反之；主轴为 Y（纵向保险库）时
     * 水平方向在 +X/+Z 均只延伸 {@code width}（高度沿 +Y 不跨区块）。按主轴精确算水平跨度，避免贴近未加载区块边界的
     * 1×9×1 纵向保险库或单列长保险库误判为跨未加载区块。
     */
    private static boolean multiblockLoaded(Level level, BlockPos controllerPos, BlockEntity controller) {
        int dx = 0;
        int dz = 0;
        if (controller instanceof IMultiBlockEntityContainer multi) {
            Direction.Axis axis = multi.getMainConnectionAxis();
            dx = Math.max(0, (axis == Direction.Axis.X ? multi.getHeight() : multi.getWidth()) - 1);
            dz = Math.max(0, (axis == Direction.Axis.Z ? multi.getHeight() : multi.getWidth()) - 1);
        }
        return chunkNow(level, controllerPos) != null
                && chunkNow(level, controllerPos.offset(dx, 0, 0)) != null
                && chunkNow(level, controllerPos.offset(0, 0, dz)) != null
                && chunkNow(level, controllerPos.offset(dx, 0, dz)) != null;
    }

    private VaultInventoryReader() {
    }
}

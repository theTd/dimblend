package dimblend.experience.compat.tavern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;

/**
 * 酒桶龙头（{@code kaleidoscope_tavern:tap}）只能挂在"酿造酒桶"侧壁正中出酒口上的判定。
 *
 * <p>复刻 {@code TapBlock#isValidConnection}（上游是 private，不可直接调用/注入，故在这里
 * 落一份可单测的纯规则）：王壁垒层（{@code LAYER == WALL}）的 4 个正中格，
 * 各自只接受同向龙头。</p>
 *
 * <ol>
 *   <li>{@code FACING=NORTH, INDEX=1} → 只接受朝北的龙头；</li>
 *   <li>{@code FACING=SOUTH, INDEX=7} → 只接受朝南的龙头；</li>
 *   <li>{@code FACING=WEST, INDEX=3} → 只接受朝西的龙头；</li>
 *   <li>{@code FACING=EAST, INDEX=5} → 只接受朝东的龙头。</li>
 * </ol>
 *
 * <p>龙头位置 = 酒桶原点（3×3×3 的底层中心）之上 1 格、再沿桶朝向出 2 格（出酒口外侧那格）。
 * 与 {@code BarrelBlockMixin} 拆除时的取值同口径，避免两处走偏。</p>
 */
public final class TapAttachmentRules {

    /** 酒桶原点（{@code BarrelBlock#getOriginPos} 的返回值）之上多少格是龙头所在层。 */
    public static final int TAP_LAYER_OFFSET = 1;
    /** 沿桶朝向往外多少格是龙头。 */
    public static final int TAP_OUTWARD_OFFSET = 2;

    /** 龙头应该占据的方块位置。 */
    public static BlockPos tapPos(BlockPos origin, Direction barrelFacing) {
        return origin.above(TAP_LAYER_OFFSET).relative(barrelFacing, TAP_OUTWARD_OFFSET);
    }

    /** 该（层, 序号, 桶朝向）组合是否就是"侧壁出酒口"。 */
    public static boolean isSpoutCell(AttachFace layer, int index, Direction barrelFacing) {
        if (layer != AttachFace.WALL) {
            return false;
        }
        return switch (barrelFacing) {
            case NORTH -> index == 1;
            case SOUTH -> index == 7;
            case WEST -> index == 3;
            case EAST -> index == 5;
            default -> false;
        };
    }

    /** 桶状态 + 龙头朝向是否构成合法连接（与上游 {@code isValidConnection} 等价）。 */
    public static boolean isValidConnection(AttachFace layer, int index, Direction barrelFacing, Direction tapFacing) {
        return barrelFacing == tapFacing && isSpoutCell(layer, index, barrelFacing);
    }

    private TapAttachmentRules() {
    }
}

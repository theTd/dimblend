package dimblend.blocks.compat.create.funnel;

import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;

import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * 卸料漏斗的"纯算术/纯判定"层：不依赖任何方块注册、方块实体或 Minecraft 启动，
 * 因此可以被 JUnit 直接调用（与 {@code dimblend-radio} 的
 * {@code JukeboxGoggleLayout} 同一思路——把要单测的常量与分支从 mixin/方块类里拆出来）。
 *
 * <p>所有数值与分支条件都逐行对齐 Create 6.0.10：</p>
 * <ul>
 *   <li>{@link #slotOffset}/{@link #slotSideActive}/{@link #slotRotation} ←
 *       {@code FunnelFilterSlotPositioning}（过滤器图标的位置、可见面与朝向）；</li>
 *   <li>{@link #flapOffset} ← {@code FunnelBlockEntity#getFlapOffset()}（门帘偏移）；</li>
 *   <li>{@link #resolveNeighborShape}/{@link #normalizeComputedShape} ←
 *       {@code BeltFunnelBlock#updateShape}（形态在邻居变化时的保持规则）；</li>
 *   <li>{@link #goggleLine} ← catnip {@code LangBuilder#forGoggles}（护目镜行首 16px 缩进）。</li>
 * </ul>
 */
public final class DumpFunnelLayout {

    /**
     * 护目镜弹窗行首缩进。Create 的 {@code GoggleOverlayRenderer} 把 16×16 的护目镜图标画在
     * 弹窗左上角，catnip 的 {@code LangBuilder#forGoggles(list)} 因此会给每行文字加
     * {@code Strings.repeat(' ', getIndents(font, 4))}——默认字体下 4px/空格 × 4 = 16px，即 4 个空格。
     * 这里用字面量等价实现，避免依赖客户端 {@code Minecraft.getInstance().font}。
     */
    public static final String GOGGLE_GUTTER = "    ";

    /** 传送带漏斗形态（与 Create {@code BeltFunnelBlock.Shape} 同名，但不持有对方类型）。 */
    public enum Shape {
        RETRACTED, EXTENDED, PULLING, PUSHING;

        public static Shape of(BeltFunnelBlock.Shape shape) {
            return switch (shape) {
                case RETRACTED -> RETRACTED;
                case EXTENDED -> EXTENDED;
                case PULLING -> PULLING;
                case PUSHING -> PUSHING;
            };
        }

        public BeltFunnelBlock.Shape toCreate() {
            return switch (this) {
                case RETRACTED -> BeltFunnelBlock.Shape.RETRACTED;
                case EXTENDED -> BeltFunnelBlock.Shape.EXTENDED;
                case PULLING -> BeltFunnelBlock.Shape.PULLING;
                case PUSHING -> BeltFunnelBlock.Shape.PUSHING;
            };
        }
    }

    /** 过滤器图标的朝向方案（对应 Create {@code FunnelFilterSlotPositioning#rotate} 的四条分支）。 */
    public enum SlotRotation {
        /** 立式漏斗：只做 {@code Sided} 旋转。 */
        VERTICAL,
        /** 传送带漏斗收回态：只做 {@code Sided} 旋转。 */
        BELT_SIDED,
        /** 传送带漏斗 PULLING/PUSHING：{@code Sided} 之后再向前倾 22.5°。 */
        BELT_SIDED_TILTED,
        /** 普通水平漏斗：{@code Sided} 之后再向前倾 22.5°。 */
        FUNNEL_TILTED,
        /** 传送带漏斗延展态：压平贴到延展外壳的顶面。 */
        FLATTENED
    }

    private DumpFunnelLayout() {
    }

    /**
     * 带护目镜缩进的一行护目镜文字（灰字，行首 16px 给图标让位）。
     */
    public static Component goggleLine(String translationKey) {
        return Component.literal(GOGGLE_GUTTER)
                .append(Component.translatable(translationKey))
                .withStyle(ChatFormatting.GRAY);
    }

    /**
     * 过滤器图标在方块局部坐标系中的偏移。
     *
     * @param beltShape 传送带漏斗形态；普通漏斗（含立式）传 {@code null}
     */
    public static Vec3 slotOffset(Direction side, Direction funnelFacing, Shape beltShape) {
        float horizontalAngle = AngleHelper.horizontalAngle(side);
        float stateAngle = AngleHelper.horizontalAngle(funnelFacing);
        if (beltShape != null) {
            return switch (beltShape) {
                // 延展：图标随"漏斗朝向"旋转后推到延展外壳的上方、靠近喷口
                case EXTENDED -> VecHelper.rotateCentered(VecHelper.voxelSpace(8, 15.5, 13), stateAngle,
                        Direction.Axis.Y);
                // 垂直形态（正对/背对传送带）：贴在下侧
                case PULLING, PUSHING -> VecHelper.rotateCentered(VecHelper.voxelSpace(8, 12, 8.675),
                        horizontalAngle, Direction.Axis.Y);
                // 收回态
                default -> VecHelper.rotateCentered(VecHelper.voxelSpace(8, 13, 7.5), horizontalAngle,
                        Direction.Axis.Y);
            };
        }
        if (!funnelFacing.getAxis().isHorizontal()) {
            Vec3 southLocation = VecHelper.voxelSpace(8, funnelFacing == Direction.DOWN ? 14 : 2, 15.5);
            return VecHelper.rotateCentered(southLocation, horizontalAngle, Direction.Axis.Y);
        }
        return VecHelper.rotateCentered(VecHelper.voxelSpace(8, 12.2, 8.55), horizontalAngle, Direction.Axis.Y);
    }

    /** 该面是否显示图标。 */
    public static boolean slotSideActive(Direction side, Direction funnelFacing, Shape beltShape) {
        if (funnelFacing == null) {
            return false;
        }
        if (funnelFacing.getAxis().isVertical()) {
            return side.getAxis().isHorizontal();
        }
        if (beltShape == Shape.EXTENDED) {
            return side == Direction.UP;
        }
        return side == funnelFacing;
    }

    /** 图标朝向方案。{@code plainFunnel} = 状态所属方块是 Create 的 {@code FunnelBlock} 派生。 */
    public static SlotRotation slotRotation(Direction funnelFacing, Shape beltShape, boolean plainFunnel) {
        if (funnelFacing == null || funnelFacing.getAxis().isVertical()) {
            return SlotRotation.VERTICAL;
        }
        if (beltShape != null) {
            if (beltShape == Shape.EXTENDED) {
                return SlotRotation.FLATTENED;
            }
            return beltShape == Shape.PULLING || beltShape == Shape.PUSHING
                    ? SlotRotation.BELT_SIDED_TILTED
                    : SlotRotation.BELT_SIDED;
        }
        return plainFunnel ? SlotRotation.FUNNEL_TILTED : SlotRotation.FLATTENED;
    }

    /**
     * 门帘（flap）相对方块中心的偏移。对齐 Create {@code FunnelBlockEntity#getFlapOffset()}：
     * 普通漏斗恒 {@code -0.0625}，传送带漏斗按形态取 {@code 0.5 / -0.125 / 0.0}。
     */
    public static float flapOffset(Shape beltShape) {
        if (beltShape == null) {
            return -0.0625f;
        }
        return switch (beltShape) {
            case EXTENDED -> 0.5f;
            case PULLING, PUSHING -> -0.125f;
            default -> 0.0f;
        };
    }

    /**
     * 邻居/形态更新后的新形态，逐条对齐 Create {@code BeltFunnelBlock#updateShape}：
     * <ol>
     *   <li>与当前相同 → 保持；</li>
     *   <li>算出 PUSHING、当前 PULLING → 保持；</li>
     *   <li>算出 RETRACTED、当前 EXTENDED → <b>保持</b>（延展态只有扳手能收回，
     *       否则延展后任何相邻方块变化都会把漏斗打回收回态）；</li>
     *   <li>其余写入新形态。</li>
     * </ol>
     */
    public static Shape resolveNeighborShape(Shape updated, Shape current) {
        if (updated == current) {
            return current;
        }
        if (updated == Shape.PUSHING && current == Shape.PULLING) {
            return current;
        }
        if (updated == Shape.RETRACTED && current == Shape.EXTENDED) {
            return current;
        }
        return updated;
    }

    /**
     * 卸料漏斗没有"从传送带上抽取"这半边，Create 计算出的 PULLING 只是中间值：
     * 只要当前不是 PULLING，就折成 PUSHING，避免漏斗掉进一个永远推不动、也不该出现的形态。
     */
    public static Shape normalizeComputedShape(Shape computed, Shape current) {
        return computed == Shape.PULLING && current != Shape.PULLING ? Shape.PUSHING : computed;
    }
}

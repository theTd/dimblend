package dimblend.blocks.compat.rail;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * 一条跨车厢管道连接：{@code (subA, posA) <-> (subB, posB)}。
 *
 * <p>两端都是「本地连接数恰好 1」的端点管道，且必须落在两个<b>直接挂接</b>的 Sable 子层级上。
 * 记录本身不保证仍然有效——有效性由 {@link TrainTubeNetworkManager#validateLinks} 每 tick 复核。</p>
 */
public record TrainTubeLink(UUID subA, BlockPos posA, UUID subB, BlockPos posB) {

    public TrainTubeLink {
        Objects.requireNonNull(subA, "subA");
        Objects.requireNonNull(subB, "subB");
    }

    /** 该连接是否锚在给定端点上（任一方向）。 */
    public boolean anchoredAt(UUID subLevel, BlockPos pos) {
        return (subA.equals(subLevel) && posA.equals(pos)) || (subB.equals(subLevel) && posB.equals(pos));
    }

    /** 给定另一端时，返回对侧的子层级。 */
    public UUID otherSubLevel(UUID subLevel, BlockPos pos) {
        return subA.equals(subLevel) && posA.equals(pos) ? subB : subA;
    }

    /** 给定另一端时，返回对侧的位置。 */
    public BlockPos otherPos(UUID subLevel, BlockPos pos) {
        return subA.equals(subLevel) && posA.equals(pos) ? posB : posA;
    }

    /** 两条记录是否描述同一对端点（不分方向）。 */
    public boolean samePair(TrainTubeLink other) {
        if (other == null) {
            return false;
        }
        return (subA.equals(other.subA) && posA.equals(other.posA)
                && subB.equals(other.subB) && posB.equals(other.posB))
                || (subA.equals(other.subB) && posA.equals(other.posB)
                && subB.equals(other.subA) && posB.equals(other.posA));
    }

    /** 该连接是否涉及某个子层级（拆除该子层级时要连带清理）。 */
    public boolean touches(UUID subLevel) {
        return subA.equals(subLevel) || subB.equals(subLevel);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("subA", subA);
        tag.putLong("posA", posA.asLong());
        tag.putUUID("subB", subB);
        tag.putLong("posB", posB.asLong());
        return tag;
    }

    public static TrainTubeLink load(CompoundTag tag) {
        return new TrainTubeLink(tag.getUUID("subA"), BlockPos.of(tag.getLong("posA")),
                tag.getUUID("subB"), BlockPos.of(tag.getLong("posB")));
    }
}

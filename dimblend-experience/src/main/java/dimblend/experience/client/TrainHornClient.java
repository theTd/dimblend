package dimblend.experience.client;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.create.TrainHornPayload;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 列车汽笛客户端状态：{@link TrainHornPayload} 落地处。
 *
 * <p>「张嘴动画 + 音符粒子」窗口与声音实例共用同一份到达时刻（客户端 gameTime +
 * 素材时长），按汽笛 plot 坐标登记；{@code WhistleHornClientMixin} 在汽笛 BE 的客户端
 * tick 里查询本类驱动动画/粒子。窗口过期在读取时惰性清除，新包到达时也顺手清扫。</p>
 */
public final class TrainHornClient {

    /** 鸣笛窗口：截止 tick（客户端 gameTime）+ 音效种类（粒子配色用）。 */
    private record HornWindow(long untilTick, TrainHorn.HornKind kind) {
    }

    private static final Map<BlockPos, HornWindow> WINDOWS = new HashMap<>();

    /** S2C 处理器落点（客户端线程）。 */
    public static void apply(TrainHornPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        int ordinal = payload.hornKind() & 0xFF;
        TrainHorn.HornKind[] kinds = TrainHorn.HornKind.values();
        if (ordinal >= kinds.length) {
            return;
        }
        TrainHorn.HornKind kind = kinds[ordinal];
        long now = mc.level.getGameTime();
        sweepExpired(now);
        WINDOWS.put(payload.pos().immutable(), new HornWindow(now + kind.durationTicks(), kind));
        // 距离门：起播时已在 512 格外就不占声道（音量曲线本身也会给 0）
        Vec3 world = SableWorldPosition.project(mc.level, Vec3.atCenterOf(payload.pos()));
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (camera.distanceTo(world) >= TrainHorn.MAX_RADIUS) {
            return;
        }
        mc.getSoundManager().play(
                new TrainHornSoundInstance(kind.sound(), payload.pos(), payload.pitch(), kind.durationTicks()));
    }

    /** 汽笛客户端 tick 查询：当前是否处于鸣笛窗口（过期即清除并视为未鸣笛）。 */
    public static boolean isHornActive(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return false;
        }
        HornWindow window = WINDOWS.get(pos);
        if (window == null) {
            return false;
        }
        if (mc.level.getGameTime() >= window.untilTick()) {
            WINDOWS.remove(pos);
            return false;
        }
        return true;
    }

    /**
     * 鸣笛窗口内每客户端 tick 由汽笛 BE mixin 调用：音符粒子向前冲出。
     *
     * <p>原版 {@code NoteParticle} 的第一速度槽被固定为颜色参数且自带上扬
     * （{@code yd += 0.2}、寿命 6 tick），无法靠初速度做前冲——改用「发射位置沿朝向
     * 锯齿推进」表现前冲：每 12 tick 一个脉冲，脉冲内发射点向前推进（节奏读取为
     * 短促气浪）。发射点旋转数学参考原生蒸汽粒子（口部朝向），但原点刻意抬到
     * 口部上方（约 y+1.4，高于原生蒸汽的管内起点 y+0.55），让短命的音符从嘴尖
     * 可见位置出发。Sable 结构上投影到世界坐标后加进主客户端 level，保证在结构
     * 当前位置可见（朝向取 plot 坐标系 FACING，不随结构姿态旋转——在案限制，
     * 见 spec L6）。</p>
     */
    public static void tickHornParticles(WhistleBlockEntity be) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        BlockPos pos = be.getBlockPos();
        HornWindow window = WINDOWS.get(pos);
        if (window == null) {
            return;
        }
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof WhistleBlock)) {
            return;
        }
        Direction facing = state.getValue(WhistleBlock.FACING);
        float angle = 180 + AngleHelper.horizontalAngle(facing);
        Vec3 mouth = VecHelper.rotate(new Vec3(0, 0.9, 0.45), angle, Direction.Axis.Y)
                .add(Vec3.atCenterOf(pos));
        long progress = mc.level.getGameTime() - (window.untilTick() - window.kind().durationTicks());
        double forward = (Math.max(0, progress) % 12) * 0.12;
        Vec3 step = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 point = mouth.add(step.scale(forward))
                .add((mc.level.random.nextDouble() - 0.5) * 0.08,
                        (mc.level.random.nextDouble() - 0.5) * 0.08,
                        (mc.level.random.nextDouble() - 0.5) * 0.08);
        Vec3 world = SableWorldPosition.project(mc.level, point);
        mc.level.addParticle(ParticleTypes.NOTE, world.x, world.y, world.z,
                window.kind().particleHue(), 0.0D, 0.0D);
    }

    /** 登出清理（DimBlendClient 的 LoggingOut 钩子调用）：防跨世界同坐标继承残窗。 */
    public static void reset() {
        WINDOWS.clear();
    }

    private static void sweepExpired(long now) {
        Iterator<HornWindow> it = WINDOWS.values().iterator();
        while (it.hasNext()) {
            if (now >= it.next().untilTick()) {
                it.remove();
            }
        }
    }

    private TrainHornClient() {
    }
}

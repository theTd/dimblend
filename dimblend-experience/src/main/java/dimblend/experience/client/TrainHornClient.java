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
 * tick 里查询本类驱动动画/粒子并抑制特殊模式的原生音频。停止包或断电对账清除窗口、
 * 淡出声音；重新起播与登出都会精确停止旧实例，窗口过期在读取时惰性清除。</p>
 */
public final class TrainHornClient {

    /** 鸣笛窗口：截止 tick（客户端 gameTime）+ 音效种类（粒子配色用）。 */
    private record HornWindow(long untilTick, TrainHorn.HornKind kind) {
    }

    private static final Map<BlockPos, HornWindow> WINDOWS = new HashMap<>();
    private static final Map<BlockPos, TrainHornSoundInstance> SOUNDS = new HashMap<>();

    /** S2C 处理器落点（客户端线程）。 */
    public static void apply(TrainHornPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        BlockPos pos = payload.pos().immutable();
        if (!payload.start()) {
            stopHorn(pos);
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
        TrainHornSoundInstance previous = SOUNDS.remove(pos);
        if (previous != null) {
            mc.getSoundManager().stop(previous);
        }
        WINDOWS.put(pos, new HornWindow(now + kind.durationTicks(), kind));
        // 距离门：起播时已在 512 格外就不占声道（音量曲线本身也会给 0）
        Vec3 world = SableWorldPosition.project(mc.level, Vec3.atCenterOf(pos));
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        if (camera.distanceTo(world) >= TrainHorn.MAX_RADIUS) {
            return;
        }
        TrainHornSoundInstance sound = new TrainHornSoundInstance(
                kind.sound(), pos, payload.pitch(), kind.durationTicks());
        SOUNDS.put(pos, sound);
        mc.getSoundManager().play(sound);
    }

    /**
     * 特殊供电期间彻底接管 Create 原生音频；断电对账是停止包之外的兜底。
     * 直接读客户端红石与邻接引擎，开始包到达前也不会漏出原生汽笛。
     */
    public static boolean tickSpecialMode(WhistleBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        TrainHornSoundInstance sound = SOUNDS.get(pos);
        if (sound != null && sound.isStopped()) {
            SOUNDS.remove(pos);
        }
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof WhistleBlock) || be.getLevel() == null) {
            stopHorn(pos);
            return false;
        }
        if (!state.getValue(WhistleBlock.POWERED)) {
            stopHorn(pos);
        }
        if (!TrainHorn.isSpecialMode(be.getLevel(), pos, state)) {
            return false;
        }
        if (isHornActive(pos)) {
            tickHornParticles(be);
        }
        return true;
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
            SOUNDS.remove(pos);
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

    private static void stopHorn(BlockPos pos) {
        WINDOWS.remove(pos);
        TrainHornSoundInstance sound = SOUNDS.get(pos);
        if (sound != null) {
            sound.fadeOut();
        }
    }

    /** 登出清理（DimBlendClient 的 LoggingOut 钩子调用）：防跨世界同坐标继承残窗与声音。 */
    public static void reset() {
        Minecraft mc = Minecraft.getInstance();
        for (TrainHornSoundInstance sound : SOUNDS.values()) {
            mc.getSoundManager().stop(sound);
        }
        SOUNDS.clear();
        WINDOWS.clear();
    }

    private static void sweepExpired(long now) {
        Iterator<Map.Entry<BlockPos, HornWindow>> it = WINDOWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, HornWindow> entry = it.next();
            if (now >= entry.getValue().untilTick()) {
                it.remove();
                SOUNDS.remove(entry.getKey());
            }
        }
    }

    private TrainHornClient() {
    }
}

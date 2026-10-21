package dimblend.experience.compat.create;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlock;
import com.mrh0.createaddition.blocks.modular_accumulator.ModularAccumulatorBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;

import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;

import org.jetbrains.annotations.Nullable;

/**
 * 列车汽笛（需求追加 2026-10-21）：蒸汽笛六面邻接特定引擎方块时，红石上升沿触发
 * 一次性对应音效，<b>不要求所附储罐有热源</b>（Create 原生汽笛只在锅炉激活时响，
 * 本功能补的是无热源场景的机车鸣笛）。
 *
 * <p>语义（用户拍板口径）：</p>
 * <ul>
 * <li>触发：{@code WhistleBlock.neighborChanged} TAIL 处识别 POWERED false→true 上升沿
 * （{@code WhistleHornBlockMixin}）；播放期间（锁定窗口内）的上升沿无视——
 * 上升沿触发天然满足「播完需信号断开再次输入」：信号保持高位不会产生新沿。</li>
 * <li>六面扫描按方向优先级取第一个命中：以汽笛朝向为基准
 * 正面 → 背面 → 左侧 → 右侧 → 上方 → 下方（附着储罐一面永不命中引擎）。</li>
 * <li>引擎映射：Create 蒸汽引擎 → 蒸汽音；CDG 三种柴油机
 * （普通/巨型/组合式，block instanceof 三类）→ 柴油音；CCA 电动马达/模块化电池
 * → 电动音（CCA 1.5.10 旧 {@code accumulator} 已从注册表移除，仅剩
 * {@code modular_accumulator}，字节码核实）。不考虑 Create: Sound of Steam。</li>
 * <li>音高：按汽笛尺寸（小/中/大）读 Config 每音效独立 9 键，服务端取值随包下发，
 * 客户端不读本地配置（无双端漂移）；引擎钳制区间 [0.5, 2.0] 与
 * {@code SoundEngine.calculatePitch} 一致。</li>
 * <li>锁定：时长 = 素材实测时长（ffprobe：steam 2.60s / diesel 3.04s / elec 3.06s，
 * 换素材须同步改 {@link HornKind#durationTicks}），存服务端 BE（{@link TrainHornStateAccess}，
 * 纯内存不落盘，区块卸载/重进清零——同 SteamEngineOverload 口径）。</li>
 * <li>声音下发：自定义 payload 广播到投影后世界坐标 528 格内玩家；客户端
 * {@code TrainHornSoundInstance} 每 tick 按 256 格全音量 / 512 格衰减至零的曲线
 * 手算音量（Attenuation.NONE，OpenAL 不做距离衰减但保留方向性声像），
 * 无视遮挡。Sable 结构上按结构当前世界位置播（{@link SableWorldPosition} 投影）。</li>
 * </ul>
 */
public final class TrainHorn {

    /** 距离曲线：256 格内全音量。 */
    public static final double FLAT_RADIUS = 256.0D;
    /** 距离曲线：512 格衰减至零（以外静音）。 */
    public static final double MAX_RADIUS = 512.0D;
    /** 服务端广播半径（世界坐标，略大于 MAX_RADIUS 留余量）。 */
    private static final double BROADCAST_RADIUS = MAX_RADIUS + 16.0D;

    /** 汽笛音效种类；不含任何外部 mod 类引用（客户端安全直接触达）。 */
    public enum HornKind {
        STEAM(ModSounds.TRAIN_HORN_STEAM, 55, 0.60D),
        DIESEL(ModSounds.TRAIN_HORN_DIESEL, 64, 0.08D),
        ELEC(ModSounds.TRAIN_HORN_ELEC, 64, 0.35D);

        private final DeferredHolder<SoundEvent, SoundEvent> sound;
        /** 锁定/张嘴时长（tick）：素材时长上取整 + 少量收尾余量。 */
        private final int durationTicks;
        /** 音符粒子色相参数（NoteParticle 第一槽为颜色而非速度）。 */
        private final double particleHue;

        HornKind(DeferredHolder<SoundEvent, SoundEvent> sound, int durationTicks, double particleHue) {
            this.sound = sound;
            this.durationTicks = durationTicks;
            this.particleHue = particleHue;
        }

        public SoundEvent sound() {
            return sound.get();
        }

        public int durationTicks() {
            return durationTicks;
        }

        public double particleHue() {
            return particleHue;
        }
    }

    /**
     * 红石上升沿入口（调用方保证：服务端、POWERED 刚翻转为 true、方块仍是蒸汽笛）。
     * 锁定窗口内直接无视；无引擎邻接时不动（保持 Create 原生行为）。
     */
    public static void onPoweredEdge(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TrainHornStateAccess access)) {
            return;
        }
        long now = level.getGameTime();
        if (access.dimblend$hornUntilTick() > now) {
            return;
        }
        HornKind kind = detectEngine(level, pos, state);
        if (kind == null) {
            return;
        }
        access.dimblend$setHornLock(now + kind.durationTicks(), kind.ordinal());
        WhistleSize size = state.getValue(WhistleBlock.SIZE);
        TrainHornPayload payload = new TrainHornPayload(pos, (byte) kind.ordinal(), pitchFor(kind, size));
        Vec3 world = SableWorldPosition.project(level, pos.getCenter());
        Packet<?> packet = new ClientboundCustomPayloadPacket(payload);
        level.getServer().getPlayerList()
                .broadcast(null, world.x, world.y, world.z, BROADCAST_RADIUS, level.dimension(), packet);
    }

    /** 方向优先级扫描：正面 → 背面 → 左 → 右 → 上 → 下，取第一个命中的引擎种类。 */
    @Nullable
    private static HornKind detectEngine(ServerLevel level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(WhistleBlock.FACING);
        Direction[] order = {
                facing, facing.getOpposite(),
                facing.getCounterClockWise(), facing.getClockWise(),
                Direction.UP, Direction.DOWN};
        for (Direction dir : order) {
            HornKind kind = classify(level.getBlockState(pos.relative(dir)));
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    @Nullable
    private static HornKind classify(BlockState state) {
        Block block = state.getBlock();
        // 蒸汽笛存在即蕴含 Create 在场，Create 类无需 ModList 守卫；
        // CDG/CCA 类引用各自守卫生效前不触达（JVM 惰性解析 instanceof 操作数）
        if (block instanceof SteamEngineBlock) {
            return HornKind.STEAM;
        }
        if (ModList.get().isLoaded("createdieselgenerators") && isCdgEngine(block)) {
            return HornKind.DIESEL;
        }
        if (ModList.get().isLoaded("createaddition") && isCcaElectric(block)) {
            return HornKind.ELEC;
        }
        return null;
    }

    private static boolean isCdgEngine(Block block) {
        return block instanceof DieselEngineBlock
                || block instanceof HugeDieselEngineBlock
                || block instanceof ModularDieselEngineBlock;
    }

    private static boolean isCcaElectric(Block block) {
        return block instanceof ElectricMotorBlock
                || block instanceof ModularAccumulatorBlock;
    }

    /** 音高：服务端权威（SERVER 配置），按音效 × 尺寸取 9 键之一。 */
    private static float pitchFor(HornKind kind, WhistleSize size) {
        double value = switch (kind) {
            case STEAM -> switch (size) {
                case SMALL -> Config.TRAIN_HORN_STEAM_PITCH_SMALL.get();
                case MEDIUM -> Config.TRAIN_HORN_STEAM_PITCH_MEDIUM.get();
                case LARGE -> Config.TRAIN_HORN_STEAM_PITCH_LARGE.get();
            };
            case DIESEL -> switch (size) {
                case SMALL -> Config.TRAIN_HORN_DIESEL_PITCH_SMALL.get();
                case MEDIUM -> Config.TRAIN_HORN_DIESEL_PITCH_MEDIUM.get();
                case LARGE -> Config.TRAIN_HORN_DIESEL_PITCH_LARGE.get();
            };
            case ELEC -> switch (size) {
                case SMALL -> Config.TRAIN_HORN_ELEC_PITCH_SMALL.get();
                case MEDIUM -> Config.TRAIN_HORN_ELEC_PITCH_MEDIUM.get();
                case LARGE -> Config.TRAIN_HORN_ELEC_PITCH_LARGE.get();
            };
        };
        return (float) value;
    }

    private TrainHorn() {
    }
}

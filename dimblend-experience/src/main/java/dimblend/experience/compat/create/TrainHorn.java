package dimblend.experience.compat.create;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlock;
import com.mrh0.createaddition.blocks.modular_accumulator.ModularAccumulatorBlock;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock.WhistleSize;
import com.simibubi.create.content.decoration.steamWhistle.WhistleExtenderBlock;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.content.kinetics.steamEngine.SteamEngineBlock;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;

import dimblend.experience.DimBlend;
import dimblend.experience.ModSounds;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredHolder;

import org.jetbrains.annotations.Nullable;

/**
 * 列车汽笛（需求追加 2026-10-21、同日两轮拍板、2026-10-24 修订、2026-10-26 音高改版、
 * 2026-10-26 起播即完整播放）。
 * 蒸汽笛六面邻接特定引擎方块时进入「特殊模式」，红石上升沿触发一次性对应音效，
 * <b>不要求所附储罐有热源</b>（Create 原生汽笛只在锅炉激活时响，本功能补的是无热源
 * 场景的机车鸣笛）。
 *
 * <p>语义（用户拍板口径）：</p>
 * <ul>
 * <li><b>特殊模式</b>（2026-10-26 改版）：六面邻接引擎即成立，<b>与红石供电无关</b>；
 * 标志与音高都存 BE 且落盘。引擎新邻接、汽笛放置或读档后首次 tick 发现邻接而标志
 * 为假时算「进入」：音高重置 v=1.0、Create 尺寸重置 MEDIUM。离开只清标志，
 * 再进入再次重置。</li>
 * <li>触发（2026-10-26 规则 B，用户拍板）：{@code WhistleBlock.neighborChanged}
 * TAIL 处只认 POWERED false→true 上升沿（{@code WhistleHornBlockMixin}）。起播后锁定到
 * 素材时长结束：<b>断电不改锁、不下发停止包</b>，片段一定播满全长（短 ON 段不会把汽笛
 * 切成听不见的气声）；锁定期间的上升沿（快速时钟、连续扳动拉杆）一律无视；锁定过期后
 * 还需要新的上升沿——持续高电平不重播，断电再上电才再次触发。特殊模式期间 Create
 * 原生汽笛声与蒸汽粒子完全禁用。</li>
 * <li>六面扫描按方向优先级取第一个命中：以汽笛朝向为基准
 * 正面 → 背面 → 左侧 → 右侧 → 上方 → 下方（附着储罐一面永不命中引擎）。</li>
 * <li>引擎映射：Create 蒸汽引擎 → 蒸汽音；CDG 三种柴油机
 * （普通/巨型/组合式，block instanceof 三类）→ 柴油音；CCA 电动马达/模块化电池
 * → 电动音（CCA 1.5.10 旧 {@code accumulator} 已从注册表移除，仅剩
 * {@code modular_accumulator}，字节码核实）。不考虑 Create: Sound of Steam。</li>
 * <li>音高（2026-10-26 改版，取代原「音效 × 尺寸 9 键配置」）：每支汽笛自带整数刻度
 * {@code tenths ∈ [0, 20]}（v = tenths / 10，整数存储避免浮点漂移），非潜行扳手右键
 * 逐格 +0.1、2.0 后回 0.0（{@link #cycleTenths}）。尺寸随档位：0.0–0.6 小 / 0.7–1.3 中 /
 * 1.4–2.0 大（{@link #sizeForTenths}）。播放音高 = 2^(v−1)（{@link #heardPitch}）：
 * 1.0 为原声，0.0 低一个八度（0.5），2.0 高一个八度（2.0）——恰好落在引擎
 * {@code SoundEngine.calculatePitch} 的 [0.5, 2.0] 钳制区间内。</li>
 * <li>锁定：时长 = 素材实测时长（ffprobe：steam 2.60s / diesel 3.04s / elec 3.43s
 * （2026-10-22 换正确素材），换素材须同步改 {@link HornKind#durationTicks}），存服务端 BE
 * （{@link TrainHornStateAccess}，纯内存不落盘，区块卸载/重进清零——同 SteamEngineOverload
 * 口径）。音高刻度与特殊模式标志则相反：落盘并随 BE 更新包同步客户端。</li>
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

    /** 音高刻度下限/上限：v = tenths / 10 ∈ [0.0, 2.0]。 */
    public static final int PITCH_TENTHS_MIN = 0;
    public static final int PITCH_TENTHS_MAX = 20;
    /** 进入特殊模式的音高刻度：v = 1.0（原声、MEDIUM 尺寸）。 */
    public static final int PITCH_TENTHS_DEFAULT = 10;

    /**
     * 尺寸档位边界（用户口径，刻度值）：0.0–0.6 → SMALL、0.7–1.3 → MEDIUM、
     * 1.4–2.0 → LARGE，即 7 起为 MEDIUM、14 起为 LARGE。
     */
    private static final int MEDIUM_FROM_TENTHS = 7;
    private static final int LARGE_FROM_TENTHS = 14;

    /** 汽笛延长管向上连续格数上限（同 {@code WhistleBlockEntity.updatePitch} 的 24 格扫描）。 */
    private static final int EXTENDER_SCAN_LIMIT = 24;

    /** BE NBT：每支汽笛自己的音高刻度（0..20）。 */
    public static final String TAG_PITCH_TENTHS = "DimBlendWhistlePitch";
    /** BE NBT：持久化的「当前处于特殊模式」标志（与红石供电无关）。 */
    public static final String TAG_SPECIAL_MODE = "DimBlendWhistleSpecial";

    /** 护目镜第一行（仅特殊模式）：「当前处于特殊工作模式「列车汽笛」」。 */
    public static final String HUD_SPECIAL_MODE = "hud.dimblend_experience.train_horn.special_mode";
    /** 护目镜第二行：「<种类>：音高=<v>」。 */
    public static final String HUD_PITCH = "hud.dimblend_experience.train_horn.pitch";
    /** 扳手改音高的动作栏反馈：「列车汽笛 音高=<v>」。 */
    public static final String HUD_PITCH_SET = "hud.dimblend_experience.train_horn.pitch_set";

    /** 汽笛音效种类；不含任何外部 mod 类引用（客户端安全直接触达）。 */
    public enum HornKind {
        STEAM(ModSounds.TRAIN_HORN_STEAM, 55, 0.60D, "hud.dimblend_experience.train_horn.kind.steam"),
        DIESEL(ModSounds.TRAIN_HORN_DIESEL, 64, 0.08D, "hud.dimblend_experience.train_horn.kind.diesel"),
        ELEC(ModSounds.TRAIN_HORN_ELEC, 72, 0.35D, "hud.dimblend_experience.train_horn.kind.elec");

        private final DeferredHolder<SoundEvent, SoundEvent> sound;
        /** 锁定/张嘴时长（tick）：素材时长上取整 + 少量收尾余量。 */
        private final int durationTicks;
        /** 音符粒子色相参数（NoteParticle 第一槽为颜色而非速度）。 */
        private final double particleHue;
        /** 护目镜第二行的种类名文案键（蒸汽/柴油/电动）。 */
        private final String hudKey;

        HornKind(DeferredHolder<SoundEvent, SoundEvent> sound, int durationTicks, double particleHue, String hudKey) {
            this.sound = sound;
            this.durationTicks = durationTicks;
            this.particleHue = particleHue;
            this.hudKey = hudKey;
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

        /** 护目镜「<种类>：音高=…」的种类名。 */
        public String hudKey() {
            return hudKey;
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
        int tenths = access.dimblend$pitchTenths();
        float pitch = heardPitch(tenths);
        access.dimblend$setHornLock(now + kind.durationTicks(), kind.ordinal());
        // 触发时刻的音高随包下发，GameTest 以同一字段核对 2^(v−1)
        access.dimblend$setHornPitch(pitch);
        // 实机排障（2026-10-23 报"音高仍反"）：留下服务端权威的 音高刻度→播放音高 证据
        DimBlend.LOGGER.debug("train horn at {}: kind={}, tenths={}, pitch={}", pos, kind, tenths, pitch);
        broadcast(level, pos, new TrainHornPayload(pos, (byte) kind.ordinal(), pitch, true));
    }

    private static void broadcast(ServerLevel level, BlockPos pos, TrainHornPayload payload) {
        Vec3 world = SableWorldPosition.project(level, pos.getCenter());
        Packet<?> packet = new ClientboundCustomPayloadPacket(payload);
        level.getServer().getPlayerList()
                .broadcast(null, world.x, world.y, world.z, BROADCAST_RADIUS, level.dimension(), packet);
    }

    /**
     * 特殊模式判定（2026-10-26 口径：邻接引擎即成立，<b>与红石供电无关</b>）。
     * 客户端原生声抑制与服务端触发共用同一判定。
     */
    public static boolean isSpecialMode(Level level, BlockPos pos, BlockState state) {
        return kindAt(level, pos, state) != null;
    }

    /** 六面邻接引擎的种类（无引擎返回 null）；护目镜在客户端按同一判定取种类。 */
    @Nullable
    public static HornKind kindAt(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof WhistleBlock)) {
            return null;
        }
        return detectEngine(level, pos, state);
    }

    /**
     * 邻接更新 / 读档首次 tick 的特殊模式对账：引擎邻接状态与 BE 持久标志不一致时
     * 走一次「进入/离开」转换。进入重置音高与尺寸，离开只清标志（不保留任何特殊状态）。
     * 仅服务端执行；标志一致时零副作用（邻居更新路径每拍可能多次调用）。
     */
    public static void reconcile(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TrainHornStateAccess access)) {
            return;
        }
        boolean special = kindAt(level, pos, state) != null;
        if (special == access.dimblend$specialMode()) {
            return;
        }
        access.dimblend$setSpecialMode(special);
        if (special) {
            access.dimblend$setPitchTenths(PITCH_TENTHS_DEFAULT);
            applySize(level, pos, state, sizeForTenths(PITCH_TENTHS_DEFAULT));
        }
        be.setChanged();
        if (be instanceof SyncedBlockEntity synced) {
            synced.sendData();
        }
    }

    /**
     * 特殊模式下的扳手右键（2026-10-26 新增，note block 式）：非潜行 + Create 扳手
     * → 音高刻度 +1（2.0 后回 0.0）、尺寸随档位刷新、动作栏回显新值；<b>不发声</b>
     * （鸣笛只认红石上升沿）。潜行拆除与普通模式的扳手行为完全不接管。
     *
     * @return true = 本次右键已被接管，调用方须返回 SUCCESS 挡掉 Create 原生扳手动作
     *         （WhistleBlock 的 IWrenchable 默认 onWrenched 会循环 SIZE，必须拦下）
     */
    public static boolean onWrenchUsed(Level level, BlockPos pos, BlockState state, @Nullable Player player,
            ItemStack stack) {
        if (player == null || player.isShiftKeyDown()) {
            return false;
        }
        if (!(stack.getItem() instanceof WrenchItem)) {
            return false;
        }
        if (!(state.getBlock() instanceof WhistleBlock)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof TrainHornStateAccess access) || !access.dimblend$specialMode()) {
            return false;
        }
        // 标志是持久化代理值；真正判定仍以六面邻接为准（标志过期时交回 Create 原生扳手）
        if (kindAt(level, pos, state) == null) {
            return false;
        }
        if (level.isClientSide) {
            // 客户端只吞掉本地预测，不产生任何状态或声音
            return true;
        }
        int tenths = cycleTenths(access.dimblend$pitchTenths());
        access.dimblend$setPitchTenths(tenths);
        applySize(level, pos, state, sizeForTenths(tenths));
        be.setChanged();
        if (be instanceof SyncedBlockEntity synced) {
            synced.sendData();
        }
        player.displayClientMessage(Component.translatable(HUD_PITCH_SET, pitchText(tenths)), true);
        return true;
    }

    /** 刻度钳制到 [0, 20]（NBT 读入与命令式写入共用，防越界值破坏档位映射）。 */
    public static int clampTenths(int tenths) {
        return Math.max(PITCH_TENTHS_MIN, Math.min(PITCH_TENTHS_MAX, tenths));
    }

    /** 扳手循环：+0.1，2.0 之后回 0.0（19 → 20 → 0）。 */
    public static int cycleTenths(int tenths) {
        int clamped = clampTenths(tenths);
        return clamped >= PITCH_TENTHS_MAX ? PITCH_TENTHS_MIN : clamped + 1;
    }

    /** 刻度 → 音高值 v（0.0..2.0）。 */
    public static double pitchValue(int tenths) {
        return clampTenths(tenths) / 10.0D;
    }

    /** 刻度 → 一位小数文案（1.2），整数换算避免浮点/本地化差异。 */
    public static String pitchText(int tenths) {
        int clamped = clampTenths(tenths);
        return (clamped / 10) + "." + (clamped % 10);
    }

    /** 刻度 → Create 汽笛尺寸档位（0.0–0.6 小 / 0.7–1.3 中 / 1.4–2.0 大）。 */
    public static WhistleSize sizeForTenths(int tenths) {
        int clamped = clampTenths(tenths);
        if (clamped >= LARGE_FROM_TENTHS) {
            return WhistleSize.LARGE;
        }
        return clamped >= MEDIUM_FROM_TENTHS ? WhistleSize.MEDIUM : WhistleSize.SMALL;
    }

    /**
     * 播放音高 = 2^(v−1)：v=1.0 原声、v=0.0 低八度（0.5）、v=2.0 高八度（2.0）。
     * 值域恰好是引擎钳制区间 [0.5, 2.0]（{@code SoundEngine.calculatePitch}），无钳制失真。
     */
    public static float heardPitch(int tenths) {
        return (float) Math.pow(2.0D, pitchValue(tenths) - 1.0D);
    }

    /**
     * 把 Create 汽笛尺寸属性刷成档位值（{@link #sizeForTenths}），并同步上方延长管的
     * SIZE 属性——延长管模型按自身 SIZE 选形，只改汽笛会大小不一致。
     *
     * <p>改方块状态走 Create 扳手同款 {@code setBlock(..., 3)}（{@code IWrenchable.onWrenched}
     * → {@code KineticBlockEntity.switchToBlockState} 的标志位）；延长管额外逐格显式镜像
     * （同 {@code WhistleBlock.incrementSize} 放置延长管时写 {@code SIZE} 的做法），
     * 不依赖 {@code WhistleExtenderBlock.updateShape} 的形状更新是否级联。</p>
     */
    private static void applySize(Level level, BlockPos pos, BlockState state, WhistleSize size) {
        if (state.getValue(WhistleBlock.SIZE) != size) {
            level.setBlock(pos, state.setValue(WhistleBlock.SIZE, size), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
        }
        BlockPos above = pos.above();
        for (int i = 0; i < EXTENDER_SCAN_LIMIT; i++) {
            BlockState extender = level.getBlockState(above);
            if (!AllBlocks.STEAM_WHISTLE_EXTENSION.has(extender)) {
                return;
            }
            if (extender.getValue(WhistleExtenderBlock.SIZE) != size) {
                level.setBlock(above, extender.setValue(WhistleExtenderBlock.SIZE, size),
                        Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
            }
            above = above.above();
        }
    }

    /** 方向优先级扫描：正面 → 背面 → 左 → 右 → 上 → 下，取第一个命中的引擎种类。 */
    @Nullable
    private static HornKind detectEngine(Level level, BlockPos pos, BlockState state) {
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

    private TrainHorn() {
    }
}

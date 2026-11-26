package dimblend.experience.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import dimblend.experience.Config;

/**
 * 柴油机"活塞损坏"态（B6'，2026-11-25 LOCKED 口径）。
 *
 * <p><b>为什么要这个态</b>：原来的引信到 0 直接 {@code destroyBlock(pos, true)}，等于整机蒸发。
 * 新口径要求"过载把活塞烧坏、机器还在、用活塞修"——所以引信照旧爆响爆闪，
 * 但机器留下，转成"已损坏"：出力 0、不烧油、客户端声音停、不再当热源，
 * 每 5 tick 一缕黑烟提示玩家这里要修。</p>
 *
 * <p><b>修复入口</b>：手持 {@code createdieselgenerators:engine_piston} 右键，
 * 消耗一个活塞 + {@link CdgEngineState#repairPiston()}；巨型机是 3×3 结构，
 * 右键任意部件都会落到 controller（mixin 里按 {@code IEngine} 统一处理，
 * 巨型机的 controller 语义由 {@code ModularDieselEngineBlockEntity}-侧的同族实现保证）。</p>
 *
 * <p><b>防反拆（2026-11-27 口径）</b>：损坏的机器不允许用扳手拆走再重放来"洗掉"损坏——
 * 潜行扳手拆除走 {@code IWrenchable#onSneakWrenched}，其内部会 post 可取消的
 * {@code BlockEvent.BreakEvent}，{@link CdgEngineDamageEvents} 在那里取消并提示；
 * 非潜行扳手右键在 {@code useItemOn} 层就被挡下（见 {@code CdgEnginePistonRepairMixin}）。
 * 两处都用 {@link #WRENCH_DENIED_KEY}（"坏掉了 要先修好"）。</p>
 *
 * <p><b>破坏后的掉落（2026-11-27 口径）</b>：用扳手以外的任何方式破坏（镐子/手/钻头/
 * 爆炸/{@code level.destroyBlock}）都只掉 {@link #DAMAGED_SCRAP_COUNT} 张黄铜板，
 * 机器本体不进背包——见 {@code CdgPistonDamageDropMixin} 覆写的
 * {@code getDrops}（原版所有掉落路径都汇到那儿）。{@link #TAG_PISTON_DAMAGED} /
 * {@link #tagDamagedDrop} / {@link #restoreOnPlace} 只为改动前存档里已经带标记的引擎物品保留。</p>
 *
 * <p>本类是普通 helper（非 mixin 包），mixin 只做转发。</p>
 */
public final class CdgEngineDamage {

    /** 损坏标记写进掉落物 CUSTOM_DATA 的键。 */
    public static final String TAG_PISTON_DAMAGED = "dimblend_piston_damaged";

    /** 写进引擎 BE 的 NBT 键（跨客户端同步用；附件本身不 sync，护目镜红字要靠这个）。 */
    public static final String TAG_PISTON_DAMAGED_NBT = "DimBlendPistonDamaged";

    /** 右键损坏机器但手里不是活塞时的动作条文案。 */
    public static final String HINT_KEY = "tooltip.dimblend_experience.diesel_engine_piston_damaged";

    /** 扳手反拆被拦时的动作条文案。 */
    public static final String WRENCH_DENIED_KEY = "tooltip.dimblend_experience.diesel_engine_piston_wrench_denied";

    /** 修复成功时的动作条文案。 */
    public static final String REPAIRED_KEY = "tooltip.dimblend_experience.diesel_engine_piston_repaired";

    /** 损坏机器被"非扳手"方式破坏后掉落的黄铜板数量（2026-11-27 口径：2）。 */
    public static final int DAMAGED_SCRAP_COUNT = 2;

    /** 损坏机器右键时该做什么（纯逻辑，零 Minecraft 依赖，单测直接覆盖）。 */
    public enum RightClickAction {
        /** 机器没坏：完全不干预，交给原版 {@code useItemOn}（升级件/扳手照常）。 */
        IGNORE,
        /** 手里是活塞：走换活塞修复。 */
        REPAIR,
        /** 手里是扳手（普通或潜行右键）：挡下，提示"坏掉了 要先修好"。 */
        DENY_WRENCH,
        /** 手里是别的物品：提示"已损坏 需更换活塞"。 */
        DENY_OTHER
    }

    /**
     * 损坏机器的右键路由：活塞 → 修复；扳手 → 挡下（提示"坏掉了 要先修好"）；
     * 其它物品 → 挡下（提示"已损坏 需更换活塞"）；没坏 → 不干预。
     *
     * <p>活塞优先于扳手：手里同时拿着两样时以"能修"为准，避免挡掉唯一有用的操作。</p>
     */
    public static RightClickAction decideRightClick(boolean damaged, boolean holdingPiston, boolean holdingWrench) {
        if (!damaged) {
            return RightClickAction.IGNORE;
        }
        if (holdingPiston) {
            return RightClickAction.REPAIR;
        }
        if (holdingWrench) {
            return RightClickAction.DENY_WRENCH;
        }
        return RightClickAction.DENY_OTHER;
    }

    /**
     * 手里拿的是不是扳手：Create 的扳手物品本体，或任何落在 {@code c:tools/wrench} 的物品
     * （附属模组的扳手也得挡住，否则换个扳手就能把坏机器拆走洗掉损坏）。
     */
    public static boolean isWrench(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.is(AllItems.WRENCH.get()) || stack.is(Tags.Items.TOOLS_WRENCH);
    }

    /**
     * 损坏机器被破坏后的掉落表：{@link #DAMAGED_SCRAP_COUNT} 张黄铜板。
     *
     * <p><b>必须每次新建可变 {@link ArrayList}</b>：{@code getDrops} 的部分调用方
     * （如战利品/掉落处理的 {@code removeIf}、{@code forEach} 之外的改写）会就地改这个列表，
     * 返回不可变列表会直接抛 {@code UnsupportedOperationException}。
     */
    public static List<ItemStack> damagedScrapDrops() {
        List<ItemStack> drops = new ArrayList<>(1);
        drops.add(new ItemStack(AllItems.BRASS_SHEET.get(), DAMAGED_SCRAP_COUNT));
        return drops;
    }

    /**
     * 掉落时判"这台机器是不是坏的"（{@code getDrops} 覆写用）。
     *
     * <p>组合式（{@code ModularDieselEngineBlockEntity}）的损坏态记在 controller 上，非 controller
     * 部件被拆时自己的附件是干净的，必须再看一层 {@code getControllerBE()}；普通机与巨型机各格
     * 自带 BE，只看自己。功能开关（{@code Config.DIESEL_ENGINE_OVERLOAD}）走 {@link #isDamaged}
     * 同一口径——开关关掉时旧存档里的残留损坏位不掉黄铜板。</p>
     */
    public static boolean isDamagedForDrop(BlockEntity be) {
        if (!(be instanceof IEngine engine)) {
            return false;
        }
        if (isDamaged(engine)) {
            return true;
        }
        if (be instanceof ModularDieselEngineBlockEntity modular && !modular.isController()) {
            ModularDieselEngineBlockEntity controller = modular.getControllerBE();
            return controller != null && isDamaged(controller);
        }
        return false;
    }

    /** 损坏态黑烟散布 delta（2026-11-25 口径：0.15）。 */
    private static final double SMOKE_DELTA = 0.15;
    /** 损坏态黑烟速度（2026-11-25 口径：0.01）。 */
    private static final double SMOKE_SPEED = 0.01;
    /** 灭火音锚点选举的邻域半径（巨型机 3×3 结构最多跨 2 格）。 */
    private static final int SPEAKER_RADIUS = 2;

    /**
     * 把引擎标记为损坏并立刻同步（客户端护目镜红字、音效停转都靠这次 {@code notifyUpdate}）。
     *
     * @param engine 目标引擎 BE（三机通吃，{@code IEngine#self()} 取 {@link SmartBlockEntity}）
     * @return 是否发生了状态变化
     */
    public static boolean markDamaged(IEngine engine) {
        SmartBlockEntity self = engine.self();
        CdgEngineState state = self.getData(CdgAttachments.ENGINE_STATE);
        boolean changed = state.markPistonDamaged();
        self.setChanged();
        // 必须 notifyUpdate：附件不走 sync handler，客户端只能靠 write(clientPacket=true) 里的
        // DimBlendPistonDamaged 看到"坏了"
        self.notifyUpdate();
        return changed;
    }

    /**
     * 损坏态每 tick 的一缕黑烟（{@code minecraft:smoke} 与 {@code large_smoke} 交替）。
     * 只在服务端调用；节流按 {@code level.getGameTime()} 做，无状态。
     */
    public static void tickDamagedSmoke(ServerLevel level, BlockPos pos) {
        long gameTime = level.getGameTime();
        if (!CdgOverloadMath.shouldEmitDamagedSmoke(gameTime)) {
            return;
        }
        level.sendParticles(
                CdgOverloadMath.useLargeSmoke(gameTime) ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                1, SMOKE_DELTA, SMOKE_DELTA, SMOKE_DELTA, SMOKE_SPEED);
    }

    /**
     * 2026-11-26 口径的"损坏期间"判据（燃油门控 / 静音 / 灭火音都走这一个口径）。
     *
     * <p>客户端<b>不读</b> {@code Config.DIESEL_ENGINE_OVERLOAD}（SERVER 配置在客户端不可读），
     * 只认同步过来的 {@code pistonDamaged}：客户端拿到 true 就必须静音，否则 {@code tickClient}
     * 会继续 keepAlive 运转音。服务端额外把功能开关一起判，开关关掉时旧存档里的残留标记不生效。</p>
     */
    public static boolean isDamaged(IEngine engine) {
        SmartBlockEntity self = engine.self();
        if (self == null) {
            return false;
        }
        if (!self.getData(CdgAttachments.ENGINE_STATE).pistonDamaged) {
            return false;
        }
        Level level = self.getLevel();
        if (level == null || level.isClientSide) {
            return true;
        }
        return Config.DIESEL_ENGINE_OVERLOAD.get();
    }

    /**
     * 损坏期的"黑烟 + 偶发灭火音"每 tick 入口。
     *
     * @param soundAnchor 本格是否负责发声。普通机恒 true；组合式只有 controller true；
     *                    巨型机走 {@link #isSoleDamagedSpeaker}。多格结构若人人发声会变成
     *                    一台机器连续爆响，故收敛到单格。
     */
    public static void tickDamagedAmbience(ServerLevel level, BlockPos pos, CdgEngineState state,
            boolean soundAnchor) {
        tickDamagedSmoke(level, pos);
        if (!soundAnchor) {
            return;
        }
        state.extinguishCooldown = CdgDamageAudioMath.tickDown(state.extinguishCooldown);
        if (state.extinguishCooldown > 0) {
            return;
        }
        playExtinguish(level, pos);
        state.extinguishCooldown = CdgDamageAudioMath.nextIntervalTicks(level.random.nextFloat());
    }

    /** 灭火音本体（{@code block.fire.extinguish}，音量/音调都带随机）。 */
    private static void playExtinguish(ServerLevel level, BlockPos pos) {
        level.playSound(null,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS,
                CdgDamageAudioMath.volume(level.random.nextFloat()),
                CdgDamageAudioMath.pitch(level.random.nextFloat()));
    }

    /**
     * 多格结构里"谁负责响灭火音"的选举：邻域内若存在坐标更小的同类损坏引擎就让位，
     * 于是整个结构里坐标最小的那台唯一当选。
     *
     * <p>扫描只用 {@code getChunkNow}（不触发同步建块，见 AGENTS/skill §8.28），
     * 且只在真正的触发瞬间调用（3~6 秒一次），代价可忽略。</p>
     *
     * @param engineType 同类判定（巨型机传 {@code HugeDieselEngineBlockEntity.class}，
     *                   避免隔壁一台普通机的坐标把巨型机顶掉）
     */
    public static boolean isSoleDamagedSpeaker(ServerLevel level, BlockPos pos, Class<?> engineType) {
        for (int dy = -SPEAKER_RADIUS; dy <= SPEAKER_RADIUS; dy++) {
            for (int dz = -SPEAKER_RADIUS; dz <= SPEAKER_RADIUS; dz++) {
                for (int dx = -SPEAKER_RADIUS; dx <= SPEAKER_RADIUS; dx++) {
                    BlockPos probe = pos.offset(dx, dy, dz);
                    if (probe.equals(pos) || probe.compareTo(pos) >= 0) {
                        continue; // 只关心坐标更小的同类损坏引擎
                    }
                    if (isDamagedOfType(level, probe, engineType)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isDamagedOfType(ServerLevel level, BlockPos pos, Class<?> engineType) {
        BlockEntity be = loadedBlockEntity(level, pos);
        if (!(be instanceof SmartBlockEntity smart)) {
            return false;
        }
        if (!engineType.isInstance(be) || !(be instanceof IEngine)) {
            return false;
        }
        return smart.getData(CdgAttachments.ENGINE_STATE).pistonDamaged;
    }

    /** 非阻塞取已加载 BE：{@code getChunkNow} 拿不到就直接放弃（绝不 {@code getBlockEntity} 直调）。 */
    private static BlockEntity loadedBlockEntity(ServerLevel level, BlockPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    /** 该方块是不是三类柴油机之一（掉落物打标 / 放置还原用）。 */
    public static boolean isEngineBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DieselEngineBlock
                || block instanceof ModularDieselEngineBlock
                || block instanceof HugeDieselEngineBlock;
    }

    /** 给掉落物打"这是坏机器的活塞"标记（幂等）。 */
    public static void tagDamagedDrop(ItemStack stack) {
        if (stack.isEmpty() || hasDamagedTag(stack)) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(TAG_PISTON_DAMAGED, true));
    }

    /** 掉落物是否带损坏标记。 */
    public static boolean hasDamagedTag(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().getBoolean(TAG_PISTON_DAMAGED);
    }

    /**
     * 放置带标记的引擎物品：落地的机器直接是损坏态（防"挖了再放洗掉损坏"）。
     *
     * @return 是否真的把某个引擎打成了损坏态
     */
    public static boolean restoreOnPlace(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof IEngine engine)) {
            return false;
        }
        if (!isEngineBlock(level.getBlockState(pos))) {
            return false;
        }
        return markDamaged(engine);
    }

    /**
     * 把损坏态从 BE 附件写进 BE 的 NBT（{@code write(clientPacket=true)} 那条），
     * 让客户端也看得到。
     */
    public static void writeNbt(SmartBlockEntity be, CompoundTag tag) {
        if (be instanceof IEngine && be.getData(CdgAttachments.ENGINE_STATE).pistonDamaged) {
            tag.putBoolean(TAG_PISTON_DAMAGED_NBT, true);
        }
    }

    /**
     * 客户端从 BE 的 NBT 读回损坏态。客户端没有附件同步，读到这里才能让护目镜红字生效；
     * 服务端读档时附件自己也带了这个字段，这里再写一次是幂等兜底（附件缺失的旧存档也能修）。
     */
    public static void readNbt(SmartBlockEntity be, CompoundTag tag) {
        if (be instanceof IEngine && tag.getBoolean(TAG_PISTON_DAMAGED_NBT)) {
            be.getData(CdgAttachments.ENGINE_STATE).pistonDamaged = true;
        }
    }

    /** 动作条提示（服务端发给单个玩家）。 */
    public static void actionbar(ServerPlayer player, String key, ChatFormatting color) {
        player.displayClientMessage(Component.translatable(key).withStyle(color), true);
    }

    /** 巨型机损坏后停轴的 helper：把自己在轴的多引擎表里摘掉（避免残留末速空转）。 */
    public static void detachHugeShaft(HugeDieselEngineBlockEntity be) {
        var shaft = be.getShaft();
        if (shaft != null) {
            shaft.removeGenerator(be.getBlockPos());
        }
    }

    private CdgEngineDamage() {
    }
}

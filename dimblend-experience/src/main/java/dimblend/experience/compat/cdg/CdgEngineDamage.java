package dimblend.experience.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
 * <p><b>防反拆（2026-11-25 口径）</b>：损坏的机器不允许用扳手拆走再重放来"洗掉"损坏——
 * 扳手拆除走 {@code IWrenchable#onSneakWrenched}，其内部会 post 可取消的
 * {@code BlockEvent.BreakEvent}，{@link CdgEngineDamageEvents} 在那里取消并提示。
 * 镐子正常挖是可以的（掉落物会带上 {@link #TAG_PISTON_DAMAGED} 自定义数据，
 * 放回去仍是坏的——见 {@code CdgPistonDamageDropMixin} / {@code CdgPistonDamageRestoreMixin}）。</p>
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

    /** 损坏态黑烟散布 delta（2026-11-25 口径：0.15）。 */
    private static final double SMOKE_DELTA = 0.15;
    /** 损坏态黑烟速度（2026-11-25 口径：0.01）。 */
    private static final double SMOKE_SPEED = 0.01;

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

package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.create.TrainHornStateAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车汽笛 BE 状态（2026-10-26 音高改版后新增持久字段）：
 *
 * <ul>
 * <li><b>鸣笛锁定</b>：纯内存字段不落盘，区块卸载/重进清零（同
 * {@code SteamEngineOverloadMixin} 口径）。</li>
 * <li><b>音高刻度 + 特殊模式标志</b>：随 BE NBT 落盘（重载不丢），并随 Create 的
 * BE 更新包同步客户端——{@code write}/{@code read} 的 {@code clientPacket} 为真时是
 * 更新包路径（{@code SyncedBlockEntity.getUpdateTag → writeClient → write(tag, true)}），
 * 为假时是存档路径（{@code saveAdditional → write(tag, false)}），两侧都写才能既落盘
 * 又同步。护目镜与扳手动作栏都读这份同步值。</li>
 * <li><b>首次 tick 对账</b>：读档/放置后的第一拍（服务端）把持久标志与真实邻接比对一次，
 * 覆盖「放置时贴着引擎」与「区块外邻接变化」两类邻居更新观察不到的情况。</li>
 * </ul>
 *
 * <p>注入点均为目标类自身声明的方法，单一 RETURN / HEAD，TAIL 安全；mixin 包外不引用
 * 本类（状态统一走 {@link TrainHornStateAccess}）。</p>
 */
@Mixin(WhistleBlockEntity.class)
public abstract class WhistleHornBlockEntityMixin implements TrainHornStateAccess {

    /** 鸣笛锁定截止 tick（服务端 gameTime），0 = 未锁定。 */
    @Unique
    private long dimblend$hornUntilTick;

    /** 当前/最近一次锁定的音效种类序号（{@code TrainHorn.HornKind#ordinal()}），-1 = 从未触发。 */
    @Unique
    private int dimblend$hornKind = -1;

    /** 最近一次触发下发的播放音高（仅供 GameTest 断言 = 2^(v−1)）。 */
    @Unique
    private float dimblend$hornPitch = 1.0F;

    /** 本汽笛自己的音高刻度（0..20；默认 10 = v 1.0）。 */
    @Unique
    private int dimblend$pitchTenths = TrainHorn.PITCH_TENTHS_DEFAULT;

    /** 持久化的特殊模式标志（邻接引擎即真，与红石无关）。 */
    @Unique
    private boolean dimblend$specialMode;

    /** 首次 tick 对账只做一次（放置/读档后）。 */
    @Unique
    private boolean dimblend$specialReconciled;

    @Override
    public long dimblend$hornUntilTick() {
        return dimblend$hornUntilTick;
    }

    @Override
    public int dimblend$hornKind() {
        return dimblend$hornKind;
    }

    @Override
    public void dimblend$setHornLock(long untilTick, int hornKind) {
        this.dimblend$hornUntilTick = untilTick;
        this.dimblend$hornKind = hornKind;
    }

    @Override
    public float dimblend$hornPitch() {
        return dimblend$hornPitch;
    }

    @Override
    public void dimblend$setHornPitch(float pitch) {
        this.dimblend$hornPitch = pitch;
    }

    @Override
    public int dimblend$pitchTenths() {
        return dimblend$pitchTenths;
    }

    @Override
    public void dimblend$setPitchTenths(int tenths) {
        this.dimblend$pitchTenths = TrainHorn.clampTenths(tenths);
    }

    @Override
    public boolean dimblend$specialMode() {
        return dimblend$specialMode;
    }

    @Override
    public void dimblend$setSpecialMode(boolean special) {
        this.dimblend$specialMode = special;
    }

    @Inject(method = "write(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$writeWhistlePitch(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        compound.putInt(TrainHorn.TAG_PITCH_TENTHS, this.dimblend$pitchTenths);
        compound.putBoolean(TrainHorn.TAG_SPECIAL_MODE, this.dimblend$specialMode);
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Z)V",
            at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$readWhistlePitch(CompoundTag compound, HolderLookup.Provider registries,
            boolean clientPacket, CallbackInfo ci) {
        if (compound.contains(TrainHorn.TAG_PITCH_TENTHS)) {
            this.dimblend$pitchTenths = TrainHorn.clampTenths(compound.getInt(TrainHorn.TAG_PITCH_TENTHS));
        }
        if (compound.contains(TrainHorn.TAG_SPECIAL_MODE)) {
            this.dimblend$specialMode = compound.getBoolean(TrainHorn.TAG_SPECIAL_MODE);
        }
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$reconcileSpecialModeOnFirstTick(CallbackInfo ci) {
        WhistleBlockEntity self = (WhistleBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide || this.dimblend$specialReconciled) {
            return;
        }
        this.dimblend$specialReconciled = true;
        TrainHorn.reconcile(level, self.getBlockPos(), self.getBlockState());
    }
}

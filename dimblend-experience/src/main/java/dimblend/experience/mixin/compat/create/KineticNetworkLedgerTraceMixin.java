package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.compat.create.KineticLedgerTrace;
import dimblend.experience.compat.create.KineticNetworkLedgerTrace;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 诊断探针：给每个 Create 应力网络挂一个账本事件环（见 {@link KineticLedgerTrace}），
 * 记录 initFromTE / addSilently / add / remove / sync / updateCapacityFor / updateStressFor
 * 每次调用瞬间的账本读数。全部只读，不改任何 Create 行为；柴油机过载探针在误报时回放。
 * 方法名取自 Create 6.0.10-281 字节码，签名全部 public。
 */
@Mixin(KineticNetwork.class)
public abstract class KineticNetworkLedgerTraceMixin implements KineticNetworkLedgerTrace {

    @Shadow
    public boolean initialized;

    @Shadow
    public Map<KineticBlockEntity, Float> sources;

    @Shadow
    public Map<KineticBlockEntity, Float> members;

    @Shadow
    private float unloadedCapacity;

    @Shadow
    private float unloadedStress;

    @Shadow
    private int unloadedMembers;

    @Unique
    private final KineticLedgerTrace dimblend$trace = new KineticLedgerTrace();

    @Override
    public KineticLedgerTrace dimblend$ledgerTrace() {
        return this.dimblend$trace;
    }

    @Inject(method = "initFromTE", at = @At("HEAD"))
    private void dimblend$traceInit(float maxStress, float currentStress, int networkSize, CallbackInfo ci) {
        this.dimblend$record("INIT", null, false, maxStress, currentStress);
    }

    @Inject(method = "addSilently", at = @At("HEAD"))
    private void dimblend$traceAddSilently(KineticBlockEntity be, float lastCapacity, float lastStress,
            CallbackInfo ci) {
        this.dimblend$record("ADD_SILENTLY", be, this.members.containsKey(be), lastCapacity, lastStress);
    }

    @Inject(method = "add", at = @At("HEAD"))
    private void dimblend$traceAdd(KineticBlockEntity be, CallbackInfo ci) {
        this.dimblend$record("ADD", be, this.members.containsKey(be), 0.0F, 0.0F);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void dimblend$traceRemove(KineticBlockEntity be, CallbackInfo ci) {
        this.dimblend$record("REMOVE", be, this.members.containsKey(be), 0.0F, 0.0F);
    }

    @Inject(method = "sync", at = @At("HEAD"))
    private void dimblend$traceSync(CallbackInfo ci) {
        this.dimblend$record("SYNC", null, false, 0.0F, 0.0F);
    }

    @Inject(method = "updateCapacityFor", at = @At("HEAD"))
    private void dimblend$traceCapacityFor(KineticBlockEntity be, float capacity, CallbackInfo ci) {
        this.dimblend$record("CAP_FOR", be, this.sources.containsKey(be), capacity, 0.0F);
    }

    @Inject(method = "updateStressFor", at = @At("HEAD"))
    private void dimblend$traceStressFor(KineticBlockEntity be, float stress, CallbackInfo ci) {
        this.dimblend$record("STRESS_FOR", be, this.members.containsKey(be), stress, 0.0F);
    }

    @Unique
    private void dimblend$record(String op, KineticBlockEntity be, boolean flag, float a, float b) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long tick = server == null ? -1L : server.getTickCount();
        String pos = be == null ? "-" : be.getBlockPos().toShortString();
        this.dimblend$trace.record(new KineticLedgerTrace.Event(tick, op, pos, flag, a, b,
                this.members.size(), this.sources.size(),
                this.unloadedCapacity, this.unloadedStress, this.unloadedMembers, this.initialized));
    }
}

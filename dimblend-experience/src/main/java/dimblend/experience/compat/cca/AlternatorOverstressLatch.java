package dimblend.experience.compat.cca;

/**
 * D8 交流发电机过载解列锁存（判据 helper；锁存字段在
 * {@code AlternatorOverstressLatchMixin} 内 @Unique 持有：不写磁盘 NBT，区块卸载/重进
 * 按新进入重判；经 Create KBE write/read 进客户端同步包供护目镜显示，见
 * {@code AlternatorLatchSyncMixin}）。
 *
 * <p>背景（字节码基线：运行 jar create 6.0.10、createaddition 1.5.10，javap 核实）：
 * 发电机的网络应力需求 = {@code KineticNetwork.getActualStressOf} 的
 * members 缓存 impact（{@code calculateStressApplied()}，恒定 MAX_STRESS/256）
 * × |{@code getTheoreticalSpeed()}| 实时读数；而 {@code updateNetwork()} 只在
 * networkDirty（拓扑增删）时重算——{@code RotationPropagator} 传播转速只 setSpeed、
 * 不 dirty 网络——故需求随速度的变化落到网络账本上有迟滞，过载后需求残留、恢复也慢。</p>
 *
 * <p>进入：kinetic 网络过载（{@code isOverStressed()}）且被驱动中（理论转速非零）
 * 且本机已在网络账本上（{@code members.containsKey(self)}）。
 * 账本门控的缘由（读档竞态，字节码核实）：读档后 network/speed/overStressed 均从
 * NBT 恢复，首 tick HEAD 即满足前两项；但此时 {@code initialize()}（SmartBlockEntity.tick
 * 首 tick 惰性调用，在本注入点之后）尚未执行——其内 {@code addSilently} 负责把存档份额
 * 从 unloadedStress 扣回并 put members，且第一句就是 {@code members.containsKey(be)}
 * 早退。若此刻 updateStressFor 抢先 put members，addSilently 将跳过结算：存档份额
 * 永远残留 unloadedStress（网络应力虚高、落盘累积），解锁后 members 与 unloaded
 * 双计。门控把读档路径的进入推迟到第二个 tick（addSilently 已结算），正常放置路径
 * attachKinetics→add() 在首 tick 前已入 members，不受影响。</p>
 *
 * <p>锁存期：需求应力强制 0（进入/退出经 {@code updateStressFor} 即时改缓存并触发
 * 重算+sync；{@code calculateStressApplied} 钩子保证锁存期网络 split/merge 重 add
 * 等任何重缓存路径仍得 0），本机造成的过载即时解除；FE 产出同样锁 0（2026-10-15
 * 拍板，只锁产出、不锁输出，buffer 存量继续流出至放空，见
 * {@code AlternatorOverstressLatchMixin}）；服务端每 tick 方块中心
 * enchanted_hit×5 + 每 20 tick motor_overstress（复用
 * {@link MotorOverstressLatch#tickEffects}，与 D7 同素材同参数）。
 * 退出（2026-10-15 拍板改口径，原"理论转速归零"作废）：理论转速与进入锁存时记录值
 * <b>不同</b>即解锁——精确比较、不加阈值（Create 转速传播是确定性浮点，同拓扑同源
 * 逐 bit 相同；split/merge 微差导致误判解锁也接受，明确不做防翻动处理）。停机（转速
 * 归零）天然满足——锁存记录值必非零（进入判据要求非零），新判据是旧判据超集；
 * 过载冻结只把 {@code getSpeed()} 读数压 0、不动 speed 字段，
 * {@code getTheoreticalSpeed()} 不受冻结影响，不会误判退出。</p>
 *
 * <p>解锁后网络需求恢复真实值，再次满足进入判据即重新锁存（重录记录值、重播进入
 * 音效/粒子、latchAge 归零）——拍板接受项：锁存的音效粒子本身就是一直播放、需要
 * 一直吵提醒玩家处理，柴油机 B3 波动（额定后 80%~100% 每 1~3 秒跳变）造成的频繁
 * 解锁→重锁不构成问题，不处理。</p>
 */
public final class AlternatorOverstressLatch {

    /**
     * 进入判据（调用方保证服务端 + 开关开启）：过载且被驱动中且本机已在网络账本上
     * （{@code onNetworkLedger} = {@code members.containsKey(self)}，防读档首 tick
     * 抢在 addSilently 结算前入账，机理见类注释）。
     */
    public static boolean shouldEnter(boolean overStressed, float theoreticalSpeed,
            boolean onNetworkLedger) {
        return overStressed && theoreticalSpeed != 0.0F && onNetworkLedger;
    }

    /**
     * 退出判据（2026-10-15 拍板）：理论转速与进入锁存时的记录值不同即解锁——精确比较、
     * 不加阈值（同拓扑同源逐 bit 相同；split/merge 微差误判解锁接受）。停机归零天然满足
     * （锁存记录值必非零），为旧"归零才解锁"判据的超集。
     */
    public static boolean shouldReset(float currentSpeed, float latchedSpeed) {
        return currentSpeed != latchedSpeed;
    }

    private AlternatorOverstressLatch() {
    }
}

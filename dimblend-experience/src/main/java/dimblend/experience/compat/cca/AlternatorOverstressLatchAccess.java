package dimblend.experience.compat.cca;

/**
 * {@code AlternatorOverstressLatchMixin} 的 @Unique 锁存字段访问口——供 Create 侧
 * {@code KineticBlockEntity} 同步 mixin（{@code AlternatorLatchSyncMixin}）以
 * instanceof 读写，避免 Create 目标类 mixin 直接引用 CCA 类（同
 * {@code ElectricMotorGeneratorStatsMixin} 的跨 jar 引用纪律，接口为本 mod 自有）。
 */
public interface AlternatorOverstressLatchAccess {

    boolean dimblend$isOverstressLatched();

    /** 客户端同步包落地（{@code read(clientPacket=true)}）时回写锁存标志。 */
    void dimblend$setOverstressLatchedFromSync(boolean latched);
}

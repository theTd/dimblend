package dimblend.experience.compat.create;

/**
 * 由 {@code KineticNetworkLedgerTraceMixin} 注入到 Create {@code KineticNetwork} 的鸭子接口：
 * 取该网络的账本事件环。仅用于柴油机过载诊断探针。
 */
public interface KineticNetworkLedgerTrace {

    KineticLedgerTrace dimblend$ledgerTrace();
}

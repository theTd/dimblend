package dimblend.experience.compat.simurail;

/**
 * E10/E11：Simurail 自动车钩上的「扳手充能」状态。
 *
 * <p>状态只由 {@code AutomaticCouplerChargeMixin} 落到
 * {@code AutomaticCouplerBlockEntity} 上；mixin 包外不引用 mixin 类本身，
 * 一律经本接口读写（同 {@code TrainHornStateAccess} 口径）。</p>
 */
public interface CouplerChargeAccess {

    /** 该车钩当前是否处于扳手充能态（与红石充能互不混用）。 */
    boolean dimblend$wrenchCharged();

    void dimblend$setWrenchCharged(boolean charged);
}
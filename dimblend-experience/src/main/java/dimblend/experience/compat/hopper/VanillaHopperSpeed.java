package dimblend.experience.compat.hopper;

/**
 * 原版漏斗速度 ×4（LOCKED DECISION #13 `hopper_2gt_single`，2026-11-25 拍板）。
 *
 * <p>原版 1.21.1 的漏斗节律写死在 {@code HopperBlockEntity#MOVE_ITEM_SPEED = 8}
 * （{@code static final int}，javac 把它内联进每个调用点，所以只能改字节码里的字面量，改字段无效）：</p>
 * <ul>
 *   <li>{@code HopperBlockEntity#tryMoveItems}：一次成功搬运后 {@code setCooldown(8)}——漏斗自己推/吸的节律；</li>
 *   <li>{@code HopperBlockEntity#tryMoveInItem}：被别的漏斗塞进来时的"自定义冷却" {@code setCooldown(8 - k)}；</li>
 *   <li>{@code VanillaInventoryCodeHooks#insertStack}：能力（capability）容器那条 {@code setCooldown(8 - k)}。</li>
 * </ul>
 *
 * <p>三处全部改成 {@link #COOLDOWN_TICKS}（= 2 tick，即 10 个/秒，原版 2.5 个/秒的 4 倍）。
 * "自定义冷却"（{@code cooldownTime > 8}）的判定阈值留在原版 8：那只是"别把别人刚设的冷却
 * 覆盖掉"的口径，跟节律无关，改了反而会和其它 mod 的漏斗冷却打架。</p>
 *
 * <p>纯常量类，零 Minecraft 依赖，单测钉 {@code 8 / COOLDOWN_TICKS == 4} 与"必须整除"两条不变式——
 * 节律必须是 20 tick 的整数因子（10 次/秒），否则漏斗会在每秒边界上抖动。</p>
 */
public final class VanillaHopperSpeed {

    /** 原版一次搬运后的冷却（{@code MOVE_ITEM_SPEED}）。 */
    public static final int VANILLA_COOLDOWN_TICKS = 8;

    /** 本 mod 的冷却：2 tick。 */
    public static final int COOLDOWN_TICKS = 2;

    /** 加速倍率（8 / 2 = 4）。 */
    public static int multiplier() {
        return VANILLA_COOLDOWN_TICKS / COOLDOWN_TICKS;
    }

    /** 每个游戏秒（20 tick）的搬运次数。 */
    public static int movesPerSecond() {
        return 20 / COOLDOWN_TICKS;
    }

    /** 节律必须是 20 tick 的整数因子，否则秒级均速会抖。 */
    public static boolean dividesSecond(int cooldownTicks) {
        return cooldownTicks > 0 && 20 % cooldownTicks == 0;
    }

    private VanillaHopperSpeed() {
    }
}

package dimblend.experience.compat.gunsmith;

import java.util.Locale;

/**
 * 「枪匠」系列纯数学口径（<b>无 Minecraft 运行态依赖</b>，单测全覆盖；
 * 严格对齐 LOCKED DECISION #20 与 Audit Default #11）。
 *
 * <ul>
 *   <li>经验等级 {@code 0..50}（0 = 新制造未修过的枪；每在铁砧实际消耗 1 个
 *       {@code create:precision_mechanism} 升 1 级，上限 50）。</li>
 *   <li>7 类枪型满修 100% 原耐久所需精密构件数：手枪 1、冲锋枪 2、霰弹枪 1、步枪 3、
 *       狙击枪 4、机枪 3、重武器 1；单件恢复量向上取整 {@code ceil(maxDamage / N)}。</li>
 *   <li>前置条件：原耐久必须未满（{@code currentDamage > 0}）；原耐久满时即便第二行蓝色条未满
 *       也禁止维修。满足前置条件后可单次灌注至双条 200%（原耐久 + 蓝色过度维修条各满），
 *       经验消耗严格等于 {@code used * 1} 级。</li>
 *   <li>等级线性插值（{@code t = level / 50.0f}，倍率数值本身不在任何 UI 显示；只有等级数字出现在
 *       TaCZ 原生 tooltip 的「等级」行，见 {@link GunsmithLevelText}）：
 *       伤害 {@code 1.0x..2.0x}、换弹速度 {@code 1.0x..1.5x}、卡壳排障速度 {@code 1.0x..3.0x}。</li>
 * </ul>
 */
public final class GunsmithMath {

    /** 枪械经验等级上限（0 = 新枪，50 = 满级）。 */
    public static final int MAX_LEVEL = 50;

    /** OpenAL 音高安全上限：防止 3x 排障速度时音高破音。 */
    public static final float MAX_SOUND_PITCH = 2.0f;

    /** 单次铁砧维修结算结果。 */
    public record RepairResult(
            boolean allowed,
            int usedMechanisms,
            int newDamage,
            int newOverDurability,
            int newLevel,
            int xpCost) {

        public static final RepairResult DENIED = new RepairResult(false, 0, 0, 0, 0, 0);
    }

    // ---- 等级 ------------------------------------------------------------

    public static int clampLevel(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    // ---- 7 类枪型维修材料口径 ------------------------------------------------

    /**
     * 按 TaCZ {@code CommonGunIndex#getType()} 字符串返回修满 100% 原耐久所需的
     * {@code create:precision_mechanism} 数量。
     *
     * <p>对应整合包全部 7 种 {@code GunTabType}：
     * pistol=1, smg=2, shotgun=1, rifle=3, sniper=4, mg=3, rpg=1；未知/空串保守返回 1。</p>
     */
    public static int mechanismsForFullRepair(String gunType) {
        if (gunType == null) {
            return 1;
        }
        return switch (gunType.toLowerCase(Locale.ROOT)) {
            case "pistol" -> 1;
            case "smg" -> 2;
            case "shotgun" -> 1;
            case "rifle" -> 3;
            case "sniper" -> 4;
            case "mg" -> 3;
            case "rpg" -> 1;
            default -> 1;
        };
    }

    /**
     * 单件精密构件恢复的耐久点数：向上取整 {@code ceil(maxDamage / N)}，
     * 避免如 {@code 1000 / 3 = 333} 导致 3 件构件只修 999 点差 1 点不满的截断误差。
     */
    public static int repairPerMechanism(int maxDamage, String gunType) {
        if (maxDamage <= 0) {
            return 0;
        }
        int n = mechanismsForFullRepair(gunType);
        return (maxDamage + n - 1) / n;
    }

    /**
     * 计算一次铁砧精密构件维修结果（LOCKED DECISION #20）：
     * <ul>
     *   <li>原耐久已满（{@code currentDamage <= 0}）直接拒绝，哪怕蓝色过度维修条未满；</li>
     *   <li>总耐久上限为 {@code 2 * maxDamage}（原耐久 + 蓝色条），溢出部分自动存入蓝色条；</li>
     *   <li>每实际消耗 1 件精密构件，经验等级 {@code +1}（封顶 {@link #MAX_LEVEL}），
     *       经验等级消耗严格为 {@code used * 1}。</li>
     * </ul>
     */
    public static RepairResult computeAnvilRepair(
            int maxDamage,
            int currentDamage,
            int currentOverDurability,
            int currentLevel,
            String gunType,
            int availableMechanisms) {
        if (maxDamage <= 0 || currentDamage <= 0 || availableMechanisms <= 0) {
            return RepairResult.DENIED;
        }
        int perItem = repairPerMechanism(maxDamage, gunType);
        if (perItem <= 0) {
            return RepairResult.DENIED;
        }
        int cap = maxDamage * 2;
        int clampedDamage = Math.min(currentDamage, maxDamage);
        int clampedOver = Math.max(0, Math.min(currentOverDurability, maxDamage));
        int total = (maxDamage - clampedDamage) + clampedOver;

        int used = 0;
        for (int i = 0; i < availableMechanisms; i++) {
            if (total >= cap) {
                break;
            }
            total = Math.min(cap, total + perItem);
            used++;
        }
        if (used == 0) {
            return RepairResult.DENIED;
        }

        int newDamage = Math.max(0, maxDamage - total);
        int newOver = Math.max(0, total - maxDamage);
        int newLevel = Math.min(MAX_LEVEL, clampLevel(currentLevel) + used);
        int xpCost = used;
        return new RepairResult(true, used, newDamage, newOver, newLevel, xpCost);
    }

    // ---- 等级倍率（0..50 均匀线性，Audit Default #11） -------------------------

    /** 伤害倍率：{@code 1.0x}（0 级）→ {@code 1.02x}（1 级）→ {@code 2.0x}（50 级）。 */
    public static float damageMultiplier(int level) {
        return 1.0f + 1.0f * (clampLevel(level) / (float) MAX_LEVEL);
    }

    /** 换弹速度倍率：{@code 1.0x}（0 级）→ {@code 1.01x}（1 级）→ {@code 1.5x}（50 级）。 */
    public static float reloadSpeedMultiplier(int level) {
        return 1.0f + 0.5f * (clampLevel(level) / (float) MAX_LEVEL);
    }

    /** 卡壳排障速度倍率：{@code 1.0x}（0 级）→ {@code 1.04x}（1 级）→ {@code 3.0x}（50 级）。 */
    public static float unjamSpeedMultiplier(int level) {
        return 1.0f + 2.0f * (clampLevel(level) / (float) MAX_LEVEL);
    }

    /** 换弹音效音高倍率（与换弹速度一致：{@code 1.0x..1.5x}）。 */
    public static float reloadSoundPitch(int level) {
        return reloadSpeedMultiplier(level);
    }

    /** 排障音效音高倍率（受 OpenAL 上限限制封顶 {@link #MAX_SOUND_PITCH}：{@code 1.0x..2.0x}）。 */
    public static float unjamSoundPitch(int level) {
        return Math.min(MAX_SOUND_PITCH, unjamSpeedMultiplier(level));
    }

    /** 按排障速度倍率缩短排障毫秒时长，至少保留 1ms。 */
    public static int scaledDurationMs(int baseMs, int level) {
        return Math.max(1, Math.round(baseMs / unjamSpeedMultiplier(level)));
    }

    private GunsmithMath() {
    }
}

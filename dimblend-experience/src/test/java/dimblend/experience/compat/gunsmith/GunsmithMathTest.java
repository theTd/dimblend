package dimblend.experience.compat.gunsmith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 「枪匠」系列数值与铁砧维修口径单元测试（LOCKED DECISION #20 & Audit Default #11）。
 */
class GunsmithMathTest {

    private static final float EPS = 1.0e-5f;

    // ---- 等级边界 -----------------------------------------------------------

    @Test
    void levelClampsToZeroThroughFifty() {
        assertEquals(50, GunsmithMath.MAX_LEVEL);
        assertEquals(0, GunsmithMath.clampLevel(-10));
        assertEquals(0, GunsmithMath.clampLevel(0));
        assertEquals(1, GunsmithMath.clampLevel(1));
        assertEquals(25, GunsmithMath.clampLevel(25));
        assertEquals(50, GunsmithMath.clampLevel(50));
        assertEquals(50, GunsmithMath.clampLevel(999));
    }

    // ---- 7 类枪型精密构件数量 & 单件向上取整恢复量 ----------------------------

    @Test
    void mechanismsForAllSevenGunTypes() {
        assertEquals(1, GunsmithMath.mechanismsForFullRepair("pistol"));
        assertEquals(2, GunsmithMath.mechanismsForFullRepair("smg"));
        assertEquals(1, GunsmithMath.mechanismsForFullRepair("shotgun"));
        assertEquals(3, GunsmithMath.mechanismsForFullRepair("rifle"));
        assertEquals(4, GunsmithMath.mechanismsForFullRepair("sniper"));
        assertEquals(3, GunsmithMath.mechanismsForFullRepair("mg"));
        assertEquals(1, GunsmithMath.mechanismsForFullRepair("rpg"));
        // 大小写不敏感 + 未知/null 保守返回 1
        assertEquals(4, GunsmithMath.mechanismsForFullRepair("SNIPER"));
        assertEquals(3, GunsmithMath.mechanismsForFullRepair("Rifle"));
        assertEquals(1, GunsmithMath.mechanismsForFullRepair(""));
        assertEquals(1, GunsmithMath.mechanismsForFullRepair("unknown"));
        assertEquals(1, GunsmithMath.mechanismsForFullRepair(null));
    }

    @Test
    void repairPerMechanismUsesCeilDivision() {
        assertEquals(800, GunsmithMath.repairPerMechanism(800, "pistol"));
        assertEquals(600, GunsmithMath.repairPerMechanism(1200, "smg"));
        assertEquals(750, GunsmithMath.repairPerMechanism(750, "shotgun"));
        // 1000 / 3 必须向上取整为 334，确保 3 件修满 1000 不差 1 点
        assertEquals(334, GunsmithMath.repairPerMechanism(1000, "rifle"));
        assertEquals(150, GunsmithMath.repairPerMechanism(600, "sniper"));
        assertEquals(417, GunsmithMath.repairPerMechanism(1250, "mg"));
        assertEquals(667, GunsmithMath.repairPerMechanism(2000, "mg"));
        assertEquals(256, GunsmithMath.repairPerMechanism(256, "rpg"));
        assertEquals(0, GunsmithMath.repairPerMechanism(0, "rifle"));
        assertEquals(0, GunsmithMath.repairPerMechanism(-10, "rifle"));
    }

    // ---- 铁砧维修：前置拒绝 / 200% 蓝条上限 / 每件 +1 级 / 1 XP 每件 -----------

    @Test
    void refusesRepairWhenOriginalDurabilityIsFullEvenIfBlueBarNotFull() {
        // 原耐久满（damage == 0），蓝条为空 -> 禁止维修
        GunsmithMath.RepairResult emptyBlue = GunsmithMath.computeAnvilRepair(1000, 0, 0, 5, "rifle", 4);
        assertFalse(emptyBlue.allowed());

        // 原耐久满（damage == 0），蓝条只有一半 -> 依然禁止维修！
        GunsmithMath.RepairResult halfBlue = GunsmithMath.computeAnvilRepair(1000, 0, 500, 5, "rifle", 4);
        assertFalse(halfBlue.allowed());

        // 原耐久满，蓝条满 -> 禁止维修
        GunsmithMath.RepairResult fullBlue = GunsmithMath.computeAnvilRepair(1000, 0, 1000, 5, "rifle", 4);
        assertFalse(fullBlue.allowed());

        // 无材料 / 不可损坏物品 -> 禁止维修
        assertFalse(GunsmithMath.computeAnvilRepair(1000, 100, 0, 0, "rifle", 0).allowed());
        assertFalse(GunsmithMath.computeAnvilRepair(0, 10, 0, 0, "rifle", 3).allowed());
    }

    @Test
    void partialRepairSpillsExcessIntoBlueOverDurabilityBar() {
        // 步枪 max=1000（N=3, perItem=334），只掉了 10 点原耐久（damage=10, over=0），放 1 件构件
        GunsmithMath.RepairResult r = GunsmithMath.computeAnvilRepair(1000, 10, 0, 0, "rifle", 1);
        assertTrue(r.allowed());
        assertEquals(1, r.usedMechanisms());
        assertEquals(0, r.newDamage());
        assertEquals(324, r.newOverDurability());
        assertEquals(1, r.newLevel());
        assertEquals(1, r.xpCost());
    }

    @Test
    void fullRepairCapsAt200PercentAndDoesNotWasteExtraMechanisms() {
        // 步枪 max=1000（perItem=334），完全空耐久（damage=1000, over=0），放入 10 件构件：
        // 3 件修到 1002（原 1000 + 蓝 2），6 件修到 2000（cap=2000），第 7..10 件不消耗！
        GunsmithMath.RepairResult r = GunsmithMath.computeAnvilRepair(1000, 1000, 0, 0, "rifle", 10);
        assertTrue(r.allowed());
        assertEquals(6, r.usedMechanisms());
        assertEquals(0, r.newDamage());
        assertEquals(1000, r.newOverDurability());
        assertEquals(6, r.newLevel());
        assertEquals(6, r.xpCost());

        // 手枪 max=800（N=1, perItem=800），掉了 80 点（damage=80, over=0），放入 5 件：
        // 第 1 件到 1520（原 800 + 蓝 720），第 2 件顶到 1600（原 800 + 蓝 800），第 3..5 件不消耗！
        GunsmithMath.RepairResult pistol = GunsmithMath.computeAnvilRepair(800, 80, 0, 10, "pistol", 5);
        assertTrue(pistol.allowed());
        assertEquals(2, pistol.usedMechanisms());
        assertEquals(0, pistol.newDamage());
        assertEquals(800, pistol.newOverDurability());
        assertEquals(12, pistol.newLevel());
        assertEquals(2, pistol.xpCost());
    }

    @Test
    void sniperFourMechanismsGainFourLevelsAndFourXp() {
        // 狙击枪 max=600（N=4, perItem=150），完全空耐久放 4 件 -> 刚好修满原耐久，+4 级，4 XP
        GunsmithMath.RepairResult r = GunsmithMath.computeAnvilRepair(600, 600, 0, 0, "sniper", 4);
        assertTrue(r.allowed());
        assertEquals(4, r.usedMechanisms());
        assertEquals(0, r.newDamage());
        assertEquals(0, r.newOverDurability());
        assertEquals(4, r.newLevel());
        assertEquals(4, r.xpCost());
    }

    @Test
    void levelCapsAtFiftyWhileStillAllowingRepair() {
        // 48 级步枪消耗 4 件 -> 等级封顶 50，依然可修满并按实际消耗收 4 XP
        GunsmithMath.RepairResult r = GunsmithMath.computeAnvilRepair(1000, 500, 0, 48, "rifle", 4);
        assertTrue(r.allowed());
        assertEquals(4, r.usedMechanisms());
        assertEquals(0, r.newDamage());
        assertEquals(836, r.newOverDurability());
        assertEquals(50, r.newLevel());
        assertEquals(4, r.xpCost());

        // 已经 50 级的枪受损后仍可正常维修，等级保持 50
        GunsmithMath.RepairResult maxed = GunsmithMath.computeAnvilRepair(800, 100, 0, 50, "pistol", 1);
        assertTrue(maxed.allowed());
        assertEquals(1, maxed.usedMechanisms());
        assertEquals(0, maxed.newDamage());
        assertEquals(700, maxed.newOverDurability());
        assertEquals(50, maxed.newLevel());
        assertEquals(1, maxed.xpCost());
    }

    /**
     * 用户口径（2026-11-26）：新枪 0 级，第 1 次维修后 1 级，……，第 50 次维修后 50 级，
     * 第 51 次维修后仍是 50（不溢出）；并且只有 50 级在 tooltip 上带 {@code MAX}。
     *
     * <p>每一轮模拟"开火打掉 10 点原耐久 → 用 1 件精密构件修回来"：手枪 {@code maxDamage=800}、
     * {@code perItem=800}，所以单件刚好修满且必然只消耗 1 件。</p>
     */
    @Test
    void repairLadderRaisesLevelOnePerRepairUpToFiftyAndNeverOverflows() {
        int maxDamage = 800;
        String gunType = "pistol";
        int level = 0;

        assertEquals("0", GunsmithLevelText.levelText(level), "新制造的枪必须是 0 级且不显示 MAX");

        for (int repair = 1; repair <= 51; repair++) {
            GunsmithMath.RepairResult r = GunsmithMath.computeAnvilRepair(maxDamage, 10, 0, level, gunType, 1);
            assertTrue(r.allowed(), "第 " + repair + " 次维修应允许");
            assertEquals(1, r.usedMechanisms(), "第 " + repair + " 次维修只消耗 1 件");
            assertEquals(1, r.xpCost(), "第 " + repair + " 次维修收 1 级经验");

            level = r.newLevel();
            int expected = Math.min(GunsmithMath.MAX_LEVEL, repair);
            assertEquals(expected, level, "第 " + repair + " 次维修后的等级");
            assertTrue(level <= GunsmithMath.MAX_LEVEL, "等级绝不超过 50");

            if (level < GunsmithMath.MAX_LEVEL) {
                assertEquals(Integer.toString(level), GunsmithLevelText.levelText(level),
                        "未满级只显示数字，不得出现 MAX");
            } else {
                assertEquals("50 (MAX)", GunsmithLevelText.levelText(level), "满级才显示 50 (MAX)");
            }
        }

        // 第 51 次之后仍然是 50 级、仍然显示 50 (MAX)
        assertEquals(50, level);
        assertEquals("50 (MAX)", GunsmithLevelText.levelText(level));
    }

    // ---- 等级倍率（0 / 1 / 25 / 50 精确锚点） -------------------------------

    @Test
    void damageMultiplierExactAnchors() {
        assertEquals(1.00f, GunsmithMath.damageMultiplier(-5), EPS);
        assertEquals(1.00f, GunsmithMath.damageMultiplier(0), EPS);
        assertEquals(1.02f, GunsmithMath.damageMultiplier(1), EPS);
        assertEquals(1.50f, GunsmithMath.damageMultiplier(25), EPS);
        assertEquals(2.00f, GunsmithMath.damageMultiplier(50), EPS);
        assertEquals(2.00f, GunsmithMath.damageMultiplier(80), EPS);
    }

    @Test
    void reloadSpeedMultiplierExactAnchors() {
        assertEquals(1.00f, GunsmithMath.reloadSpeedMultiplier(-1), EPS);
        assertEquals(1.00f, GunsmithMath.reloadSpeedMultiplier(0), EPS);
        assertEquals(1.01f, GunsmithMath.reloadSpeedMultiplier(1), EPS);
        assertEquals(1.25f, GunsmithMath.reloadSpeedMultiplier(25), EPS);
        assertEquals(1.50f, GunsmithMath.reloadSpeedMultiplier(50), EPS);
        assertEquals(1.50f, GunsmithMath.reloadSpeedMultiplier(100), EPS);
    }

    @Test
    void unjamSpeedMultiplierExactAnchors() {
        assertEquals(1.00f, GunsmithMath.unjamSpeedMultiplier(-1), EPS);
        assertEquals(1.00f, GunsmithMath.unjamSpeedMultiplier(0), EPS);
        assertEquals(1.04f, GunsmithMath.unjamSpeedMultiplier(1), EPS);
        assertEquals(2.00f, GunsmithMath.unjamSpeedMultiplier(25), EPS);
        assertEquals(3.00f, GunsmithMath.unjamSpeedMultiplier(50), EPS);
        assertEquals(3.00f, GunsmithMath.unjamSpeedMultiplier(99), EPS);
    }

    @Test
    void soundPitchAndUnjamDurationScaling() {
        assertEquals(1.00f, GunsmithMath.reloadSoundPitch(0), EPS);
        assertEquals(1.25f, GunsmithMath.reloadSoundPitch(25), EPS);
        assertEquals(1.50f, GunsmithMath.reloadSoundPitch(50), EPS);

        assertEquals(1.00f, GunsmithMath.unjamSoundPitch(0), EPS);
        assertEquals(1.04f, GunsmithMath.unjamSoundPitch(1), EPS);
        assertEquals(2.00f, GunsmithMath.unjamSoundPitch(25), EPS);
        // 50 级排障 3x，但音高封顶 2.0f 防 OpenAL 破音
        assertEquals(2.00f, GunsmithMath.unjamSoundPitch(50), EPS);

        assertEquals(3000, GunsmithMath.scaledDurationMs(3000, 0));
        assertEquals(1500, GunsmithMath.scaledDurationMs(3000, 25));
        assertEquals(1000, GunsmithMath.scaledDurationMs(3000, 50));
        assertEquals(1, GunsmithMath.scaledDurationMs(1, 50));
    }
}

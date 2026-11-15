package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.dragonchase.api.DragonChaseDebugCars;
import dimblend.experience.reward.RewardWagonContent;
import dimblend.experience.reward.RewardWagonLayout;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 驾驶台调试预设表：15 种预生成车厢，龙巢车厢永远不在里面（2026-11-15）。
 */
class ChassisPresetTest {

    /**
     * 用户口径：内部包含所有预生成的列车种类，**龙巢车厢除外**。
     *
     * <p>这里逐项核对 {@link DragonChaseDebugCars#debugCarIds()}（已由 {@code DragonChaseDebugCarsTest}
     * 钉死与非 {@code NEST} 枚举一一对应）：每一种都必须能解析成 DRAGON 预设，
     * 而 {@code "nest"} 必须解析不到。把“龙巢不进表”钉成可执行断言，而不是注释。
     */
    @Test
    void everyNonNestDragonCarIsOfferedAndTheNestIsNot() {
        Set<ChassisPreset> dragon = EnumSet.noneOf(ChassisPreset.class);
        for (ChassisPreset preset : ChassisPreset.values()) {
            if (preset.family() == ChassisPreset.Family.DRAGON) {
                dragon.add(preset);
            }
        }
        assertEquals(2, dragon.size(), "末影龙追逐只提供刹车与水晶两种车厢");
        for (String id : DragonChaseDebugCars.debugCarIds()) {
            ChassisPreset preset = ChassisPreset.byId(id).orElseThrow(
                    () -> new AssertionError(id + " 必须有调试预设"));
            assertEquals(ChassisPreset.Family.DRAGON, preset.family(), id + " 的来源必须是末影龙追逐");
        }
        assertTrue(ChassisPreset.byId("nest").isEmpty(), "龙巢车厢不能出现在调试预设里");
        assertEquals(ChassisPreset.BRAKE, ChassisPreset.byId("brake").orElseThrow());
        assertEquals(ChassisPreset.CRYSTAL, ChassisPreset.byId("crystal").orElseThrow());
    }

    @Test
    void theTableHoldsExactlyTheFifteenPreGeneratedCars() {
        assertEquals(15, ChassisPreset.values().length, "2 标准底盘 + 11 奖励车厢 + 2 末影龙追逐车厢");
        int standard = 0;
        int reward = 0;
        int dragon = 0;
        for (ChassisPreset preset : ChassisPreset.values()) {
            switch (preset.family()) {
                case STANDARD -> standard++;
                case REWARD -> reward++;
                case DRAGON -> dragon++;
            }
        }
        assertEquals(2, standard);
        assertEquals(11, reward);
        assertEquals(2, dragon);
    }

    /** 每个 RewardWagonContent 都必须有一节车厢；NONE 是“这条车道没有车厢”的哨兵，不是预设。 */
    @Test
    void everyRewardContentExceptNoneHasAWagon() {
        Set<RewardWagonContent> covered = EnumSet.noneOf(RewardWagonContent.class);
        for (ChassisPreset preset : ChassisPreset.values()) {
            if (preset.family() != ChassisPreset.Family.REWARD) {
                continue;
            }
            assertTrue(covered.add(preset.content()), preset + " 重复使用了 " + preset.content());
        }
        assertEquals(11, covered.size());
        for (RewardWagonContent content : RewardWagonContent.values()) {
            if (content == RewardWagonContent.NONE) {
                assertTrue(!covered.contains(content), "NONE 不是车厢");
                continue;
            }
            assertTrue(covered.contains(content), content + " 必须有一节调试车厢");
        }
    }

    /** id 是网络包里的唯一键：必须唯一、可回查，并且语言键按 id 拼出来。 */
    @Test
    void idsAreUniqueAndRoundTrip() {
        Set<String> ids = new HashSet<>();
        for (ChassisPreset preset : ChassisPreset.values()) {
            assertTrue(ids.add(preset.id()), "duplicate preset id: " + preset.id());
            assertEquals(preset, ChassisPreset.byId(preset.id()).orElseThrow());
            assertEquals("chassis.dimblend_experience." + preset.id(), preset.translationKey());
            assertEquals(preset.translationKey() + ".description", preset.descriptionKey());
        }
        assertTrue(ChassisPreset.byId("").isEmpty());
        assertTrue(ChassisPreset.byId("POWER").isEmpty(), "id 区分大小写，包里的永远是枚举名小写");
    }

    /** 末影龙追逐缺席时只隐藏它自己的两种；标准底盘与奖励车厢始终存在。 */
    @Test
    void dragonPresetsFollowTheOptionalModPresence() {
        for (ChassisPreset preset : ChassisPreset.values()) {
            if (preset.family() == ChassisPreset.Family.DRAGON) {
                assertFalse(preset.available(false), preset + " 不能在末影龙追逐缺席时出现");
                assertTrue(preset.available(true));
            } else {
                assertTrue(preset.available(false), preset + " 不依赖末影龙追逐");
                assertTrue(preset.available(true));
            }
        }
    }

    /**
     * 权限口径：创造全开；生存默认全关，只有
     * {@link ChassisInteraction#SURVIVAL_STANDARD_CHASSIS_ENABLED} 打开时才能拿到两种标准底盘。
     */
    @Test
    void permissionsFollowTheCreativeOnlySwitch() {
        for (ChassisPreset preset : ChassisPreset.values()) {
            assertTrue(preset.permitted(true), "创造模式必须能看到所有预设");
        }
        assertEquals(ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED,
                ChassisPreset.POWER.permitted(false));
        assertEquals(ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED,
                ChassisPreset.CARRIAGE.permitted(false));
        assertFalse(ChassisPreset.SHALLOW_COPPER.permitted(false), "奖励车厢只给创造");
        assertFalse(ChassisPreset.MOTOR_WAGON.permitted(false), "奖励车厢只给创造");
        assertFalse(ChassisPreset.BRAKE.permitted(false), "末影龙追逐车厢只给创造");
        assertFalse(ChassisPreset.CRYSTAL.permitted(false), "末影龙追逐车厢只给创造");
    }

    /** 场地检查的尺寸必须等于生成器真正写下的尺寸，否则会误判“附近不空旷”。 */
    @Test
    void footprintsMatchTheBuildersTheyFeed() {
        assertEquals(ChassisBlueprint.HALF_LENGTH, ChassisPreset.POWER.halfLength());
        assertEquals(2, ChassisPreset.CARRIAGE.halfWidth());
        assertEquals(RewardWagonLayout.HALF_LENGTH, ChassisPreset.TWILIGHT_CHEST.halfLength());
        assertEquals(RewardWagonLayout.HALF_WIDTH, ChassisPreset.SHALLOW_COPPER.halfWidth());
        assertEquals(RewardWagonLayout.ORE_TOP_DY - RewardWagonLayout.STRUCTURE_DY,
                ChassisPreset.MOTOR_WAGON.height(), "奖励车厢最高一层是矿石顶");
        assertEquals(6, ChassisPreset.BRAKE.halfLength(), "末影龙追逐车厢 13 格长");
        assertEquals(3, ChassisPreset.CRYSTAL.halfWidth(), "末影龙追逐车厢 7 格宽");
        assertTrue(ChassisPreset.BRAKE.height() >= 2, "刹车信号与灯在中心 +2");
    }
}

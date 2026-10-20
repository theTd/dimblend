package dimblend.experience.gametest;

import dev.minhnh.yetanotherthirst.compat.ThirstCompat;
import dev.minhnh.yetanotherthirst.core.effect.ModEffects;
import dev.minhnh.yetanotherthirst.core.thirst.ThirstConfig;
import dev.minhnh.yetanotherthirst.core.thirst.ThirstStorage;
import dev.minhnh.yetanotherthirst.core.thirst.ThirstTicker;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Optional-mod runtime regression for YAT's real ticking and status-effect hooks. */
@PrefixGameTestTemplate(false)
public final class ThirstBedRestGameTests {
    @GameTest(template = "empty", templateNamespace = "dimblend_sleep", timeoutTicks = 60)
    public static void thirstAndStatusEffectExhaustionFreezeUntilWaking(GameTestHelper helper) {
        var player = BedRestGameTests.player(helper);
        var pos = BedRestGameTests.placeBed(helper);
        var thirst = ThirstStorage.get(player);
        thirst.setThirst(12);
        thirst.setQuenched(3);
        thirst.setExhaustion(ThirstConfig.EXHAUSTION_LIMIT + 1);
        float exhaustion = thirst.getExhaustion();
        player.setHealth(10);
        player.getFoodData().setFoodLevel(0);
        player.getFoodData().setSaturation(0);
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 1000, 1));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 1000, 1));
        player.addEffect(new MobEffectInstance(ModEffects.THIRSTY.get(), 1000, 1));
        try {
            helper.assertTrue(player.startSleepInBed(pos).right().isPresent(), "rest must start");
            helper.assertTrue(ThirstCompat.suspendsThirst(player), "rest uses YAT's existing suspension policy");
            BedRestGameTests.tick(player, 100);
            helper.assertTrue(thirst.getThirst() == 12 && thirst.getQuenched() == 3
                            && thirst.getExhaustion() == exhaustion,
                    "sleep freezes thirst, quenched reserve and exhaustion despite thirsty/hunger/nausea effects");

            thirst.setThirst(0);
            thirst.setQuenched(0);
            BedRestGameTests.tick(player, Math.max(100, ThirstConfig.DAMAGE_INTERVAL_TICKS + 1));
            helper.assertTrue(player.isSleeping() && player.getHealth() >= 12,
                    "zero thirst must not inflict dehydration damage while resting");
            player.stopSleepInBed(true, true);
            helper.assertTrue(!ThirstCompat.suspendsThirst(player), "normal thirst rules resume after waking");
            thirst.setThirst(12);
            thirst.setQuenched(3);
            thirst.setExhaustion(ThirstConfig.EXHAUSTION_LIMIT + 1);
            ThirstTicker.tick(player);
            helper.assertTrue(thirst.getQuenched() == 2, "awake thirst depletion must resume");
            helper.succeed();
        } finally {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
            ThirstStorage.remove(player);
        }
    }

    private ThirstBedRestGameTests() { }
}

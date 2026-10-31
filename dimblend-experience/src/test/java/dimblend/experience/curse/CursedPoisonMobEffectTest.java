package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import org.junit.jupiter.api.Test;

/**
 * 纯单测 JVM 里不能 {@code new MobEffect}：NeoForge 给 {@code FeatureFlags} 打了补丁，静态初始化要
 * FML 的 LoadingModList，普通 JUnit 下直接 NPE（{@code Bootstrap.bootStrap()} 同样过不去）。
 * 所以效果行为抽进 {@link CurseRules}（由 CurseRulesTest 钉住），这里只验证常量与「覆写了 cure 集合」
 * 这一结构事实；牛奶/图腾的真实解除路径留给实机验证。
 */
class CursedPoisonMobEffectTest {

    @Test
    void lastsSixtyMinutesAtLevelOne() {
        assertEquals(60 * 60 * 20, CursedPoisonMobEffect.DURATION_TICKS);
        assertEquals(72000, CursedPoisonMobEffect.DURATION_TICKS);
        assertEquals(0, CursedPoisonMobEffect.AMPLIFIER);
        assertEquals(8889187, CursedPoisonMobEffect.COLOR);
    }

    @Test
    void overridesFillEffectCuresSoMilkCannotCureIt() throws Exception {
        Method method = CursedPoisonMobEffect.class
                .getDeclaredMethod("fillEffectCures", Set.class, MobEffectInstance.class);
        assertEquals(CursedPoisonMobEffect.class, method.getDeclaringClass());
    }
}

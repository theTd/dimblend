package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.junit.jupiter.api.Test;

/**
 * 纯单测 JVM 里不能 {@code new MobEffect}：NeoForge 给 {@code FeatureFlags} 打了补丁，静态初始化要
 * FML 的 LoadingModList，普通 JUnit 下直接 NPE（{@code Bootstrap.bootStrap()} 同样过不去）。
 * 所以能不能"压到 2 点生命"只能在实机验证；这里钉住的是它依赖的那些常量与结构事实：
 * 效果时长、凋零色、-18 的瞬态 modifier、以及确实覆写了属性/解药钩子而不是走原版空表。
 */
class BindingCurseMobEffectTest {

    @Test
    void lastsSixtyMinutesAtLevelOne() {
        assertEquals(60 * 60 * 20, BindingCurseMobEffect.DURATION_TICKS);
        assertEquals(72000, BindingCurseMobEffect.DURATION_TICKS);
        assertEquals(0, BindingCurseMobEffect.AMPLIFIER);
    }

    @Test
    void usesTheVanillaWitherColourSoParticlesMatch() {
        // 原版 MobEffects.WITHER = new WitherMobEffect(HARMFUL, 7561558)。
        assertEquals(7561558, BindingCurseMobEffect.COLOR);
    }

    @Test
    void forcesMaxHealthDownToTwo() {
        // 玩家基础 20 + (-18) = 2：原版 MAX_HEALTH 的 RangedAttribute 下限是 1，2 合法。
        assertEquals(20.0D, BindingCurseMobEffect.VANILLA_BASE_MAX_HEALTH);
        assertEquals(-18.0D, BindingCurseMobEffect.HEALTH_MODIFIER_AMOUNT);
        assertEquals(2.0F, BindingCurseMobEffect.FORCED_MAX_HEALTH);
    }

    @Test
    void appliesTheMaxHealthModifierThroughItsOwnHooks() throws Exception {
        // 原版 MobEffect 的 attributeModifiers 表是空的，必须自己覆写这两个钩子；
        // 生命周期由 LivingEntity#onEffectAdded / onEffectRemoved 调用。
        Method add = BindingCurseMobEffect.class
                .getDeclaredMethod("addAttributeModifiers", AttributeMap.class, int.class);
        assertEquals(BindingCurseMobEffect.class, add.getDeclaringClass());
        Method remove = BindingCurseMobEffect.class
                .getDeclaredMethod("removeAttributeModifiers", AttributeMap.class);
        assertEquals(BindingCurseMobEffect.class, remove.getDeclaringClass());
    }

    @Test
    void overridesFillEffectCuresSoMilkAndTotemCannotCureIt() throws Exception {
        Method method = BindingCurseMobEffect.class
                .getDeclaredMethod("fillEffectCures", Set.class, MobEffectInstance.class);
        assertEquals(BindingCurseMobEffect.class, method.getDeclaringClass());
    }

    @Test
    void iconAndLanguageEntriesMatchTheRegisteredName() throws Exception {
        assertNotNull(getClass().getResourceAsStream(
                "/assets/dimblend_experience/textures/mob_effect/binding_curse.png"));
        for (String code : new String[] {"en_us", "zh_cn", "zh_tw"}) {
            JsonObject language = language(code);
            assertTrue(language.has("effect.dimblend_experience.binding_curse"), code);
            assertFalse(language.has("effect.dimblend_experience.cursed_poison"), code);
        }
    }

    private JsonObject language(String code) throws Exception {
        var stream = getClass().getResourceAsStream("/assets/dimblend_experience/lang/" + code + ".json");
        assertNotNull(stream, code);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

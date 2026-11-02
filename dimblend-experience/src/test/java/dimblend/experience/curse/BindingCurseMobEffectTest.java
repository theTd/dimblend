package dimblend.experience.curse;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.Test;

/**
 * 纯单测 JVM 里 {@code MobEffect} 的静态初始化过不去：它要 {@code BuiltInRegistries}，而 NeoForge 要求
 * 先跑 {@code Bootstrap.bootStrap()}；后者在无 FML 的普通 JUnit 里自己 NPE（{@code FeatureFlags} 被
 * NeoForge 打补丁，依赖 LoadingModList）。所以"实体真的被压到 2 点生命"仍只能实机验证；这里钉住它依赖的
 * 常量与结构事实：效果时长、凋零色、modifier 规范（id / -18 / ADD_VALUE），以及最关键的落地点——
 * 绑定诅咒必须用永久 modifier：{@code AttributeInstance#save()} 只序列化 {@code permanentModifiers}，
 * 而重登时 {@code LivingEntity#readAdditionalSaveData} 只还原 effect 实例、不重跑
 * {@code addAttributeModifiers}，瞬态 modifier 会在重登/重启后悄悄消失。
 *
 * <p>规范放在不继承 {@code MobEffect} 的 {@code BindingCurseMobEffect.ModifierSpec} 里，单测可独立加载；
 * 而"钩子确实调用 addPermanentModifier"在未 bootstrap 的 JVM 里拿不到 {@code AttributeInstance} 做行为验证，
 * 只能钉住编译产物里的方法引用（见 {@link #maxHealthModifierIsAppliedAsPermanent()}）。</p>
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
    void healthModifierIsMinusEighteenWithAddValueOperation() {
        // 断言的是生产代码造 modifier 的唯一来源（ModifierSpec.create），而不是照抄常量。
        // 不能直接调 BindingCurseMobEffect.healthModifier()：那会初始化 MobEffect 并炸在 BuiltInRegistries。
        AttributeModifier modifier = BindingCurseMobEffect.ModifierSpec.create();
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("dimblend_experience", "binding_curse_max_health"),
                modifier.id());
        assertEquals(-18.0D, modifier.amount());
        assertEquals(AttributeModifier.Operation.ADD_VALUE, modifier.operation());
    }

    @Test
    void maxHealthModifierIsAppliedAsPermanent() throws Exception {
        // AttributeInstance#save() 只写 permanentModifiers（vanilla 1.21.1 AttributeInstance.java:178-193），
        // 重登时 LivingEntity 只还原 effect 实例、不重跑 addAttributeModifiers（LivingEntity.java:720-730）。
        // 未 bootstrap 的 JVM 造不出 AttributeInstance（Holder<Attribute> 要走注册表），所以钉住编译产物里的
        // 方法引用：必须是 addPermanentModifier，且不能出现 addTransientModifier —— 正是这次回归的路径。
        byte[] classFile = readClassFile(BindingCurseMobEffect.class);
        assertTrue(containsAscii(classFile, "addPermanentModifier"),
                "绑定诅咒必须用 addPermanentModifier 落地，否则 modifier 不在 AttributeInstance#save() 里，重登即失效");
        assertFalse(containsAscii(classFile, "addTransientModifier"),
                "瞬态 modifier 不会被 AttributeInstance#save() 序列化，重登/重启后生命上限会悄悄回到 20");
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

    /** 读取某个类自己的 class 文件（getResourceAsStream 不会触发类初始化）。 */
    private static byte[] readClassFile(Class<?> type) throws Exception {
        String resource = type.getSimpleName() + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return stream.readAllBytes();
        }
    }

    /**
     * 在 class 文件里找 ASCII 子串。方法名按原样存在常量池的 Utf8 项里；源码注释不进 class 文件，
     * 所以不会因为注释里提到 addTransientModifier 而误命中。
     */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + pattern.length <= haystack.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}

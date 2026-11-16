package dimblend.experience.compat.coldsweat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-16 批次：Cold Sweat 热源常数 + 恒温石模式/红石口径 + 覆盖资源包结构。
 *
 * <p>纯 JUnit：Cold Sweat / CDG 是 {@code compileOnly}，测试运行期不在 classpath 上，
 * 所以这里只碰不引用它们的部分（{@link ColdSweatHeatSources} 的常数与换算、
 * {@link ThermolithModeRules} 的纯规则），Cold Sweat 的
 * {@code Temperature.convert(20, F, MC, false) = 20/45} 这类口径以注释写死期望值。</p>
 */
class ColdSweatBatch1116Test {

    private static final double EPSILON = 1.0E-12;

    // ---------------------------------------------------------------- 热源常数

    /**
     * 普通加热烈焰人炉：temp 20 °F、range 5、maxEffect 45 °F = 1 MC、minEffect −1 MC、
     * maxTemp 400 °F（绝对）= (400−32)/45 ≈ 8.17778 MC、minTemp −∞、fade=true。
     */
    @Test
    void kindledProfileMatchesBlazeBurnerStandard() {
        ColdSweatHeatSources.HeatProfile kindled = ColdSweatHeatSources.KINDLED;

        assertEquals(20.0 / 45.0, kindled.temperatureMc(), EPSILON);
        assertEquals(5.0, kindled.range(), EPSILON);
        assertEquals(1.0, kindled.maxEffectMc(), EPSILON);
        assertEquals(-1.0, kindled.minEffectMc(), EPSILON);
        assertEquals(8.177777777777777, kindled.maxTemperatureMc(), EPSILON);
        assertEquals(Double.NEGATIVE_INFINITY, kindled.minTemperatureMc());
        assertTrue(kindled.fade(), "fade=true 才能让 Cold Sweat 自己做距离衰减");
    }

    /**
     * 超级加热烈焰人炉：temp 30 °F = 2/3 MC、range 6、maxEffect 60 °F = 4/3 MC、
     * minEffect −4/3 MC，其余同 kindled。
     */
    @Test
    void seethingProfileMatchesBlazeBurnerStandard() {
        ColdSweatHeatSources.HeatProfile seething = ColdSweatHeatSources.SEETHING;

        assertEquals(30.0 / 45.0, seething.temperatureMc(), EPSILON);
        assertEquals(2.0 / 3.0, seething.temperatureMc(), EPSILON);
        assertEquals(6.0, seething.range(), EPSILON);
        assertEquals(4.0 / 3.0, seething.maxEffectMc(), EPSILON);
        assertEquals(-4.0 / 3.0, seething.minEffectMc(), EPSILON);
        assertEquals(8.177777777777777, seething.maxTemperatureMc(), EPSILON);
        assertEquals(Double.NEGATIVE_INFINITY, seething.minTemperatureMc());
        assertTrue(seething.fade());
    }

    /**
     * {@code Temperature.convert(F, MC, absolute)} 的复算：非绝对 = F/45，绝对 = (F−32)/45。
     * 45 °F 差 = 1 MC 是 Cold Sweat 自己的换算基准。
     */
    @Test
    void fahrenheitToMcFollowsColdSweatConversion() {
        assertEquals(1.0, ColdSweatHeatSources.fahrenheitToMc(45.0, false), EPSILON);
        assertEquals(0.0, ColdSweatHeatSources.fahrenheitToMc(32.0, true), EPSILON);
        assertEquals(8.177777777777777, ColdSweatHeatSources.fahrenheitToMc(400.0, true), EPSILON);
        assertEquals(400.0 / 45.0, ColdSweatHeatSources.fahrenheitToMc(400.0, false), EPSILON);
        // 与 profile 里的温度同源
        assertEquals(ColdSweatHeatSources.KINDLED.temperatureMc(),
                ColdSweatHeatSources.fahrenheitToMc(20.0, false), EPSILON);
    }

    // ---------------------------------------------------------------- 模式信号

    /** Celsius → MC 与 Cold Sweat 的 C→MC 分支一致（÷25）。 */
    @Test
    void celsiusToMcMatchesThresholds() {
        assertEquals(1.6, ThermolithModeRules.celsiusToMc(40.0), EPSILON);
        assertEquals(0.4, ThermolithModeRules.celsiusToMc(10.0), EPSILON);
        assertEquals(ThermolithModeRules.ICE_THRESHOLD_MC, ThermolithModeRules.celsiusToMc(40.0), EPSILON);
        assertEquals(ThermolithModeRules.MAGMA_THRESHOLD_MC, ThermolithModeRules.celsiusToMc(10.0), EPSILON);
        assertEquals(15, ThermolithModeRules.FULL_SIGNAL);
    }

    /** 冰模式：严格高于 40 °C（1.6 MC）才满 15；正好 40 °C 不输出。 */
    @Test
    void iceModeSignalsOnlyAboveFortyCelsius() {
        double threshold = ThermolithModeRules.celsiusToMc(40.0);
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, threshold));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, threshold - 1.0E-9));
        assertEquals(15, ThermolithModeRules.signalFor(ThermolithMode.ICE, ThermolithModeRules.celsiusToMc(40.1)));
        assertEquals(15, ThermolithModeRules.signalFor(ThermolithMode.ICE, ThermolithModeRules.celsiusToMc(100.0)));
        // 冷端一律不输出
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, ThermolithModeRules.celsiusToMc(0.0)));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, ThermolithModeRules.celsiusToMc(-40.0)));
    }

    /** 熔岩模式：严格低于 10 °C（0.4 MC）才满 15；正好 10 °C 不输出。 */
    @Test
    void magmaModeSignalsOnlyBelowTenCelsius() {
        double threshold = ThermolithModeRules.celsiusToMc(10.0);
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, threshold));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, threshold + 1.0E-9));
        assertEquals(15, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, ThermolithModeRules.celsiusToMc(9.9)));
        assertEquals(15, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, ThermolithModeRules.celsiusToMc(-10.0)));
        // 热端一律不输出
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, ThermolithModeRules.celsiusToMc(40.1)));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.MAGMA, ThermolithModeRules.celsiusToMc(100.0)));
    }

    /** NORMAL 由原版 tick 负责，模式重算永远不接管。 */
    @Test
    void normalModeNeverDrivesTheModeSignal() {
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, -100.0));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 0.0));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 1.6));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 100.0));
    }

    // ---------------------------------------------------------------- 交互口径

    @Test
    void vanillaIceBreakOnlyResetPolicyIsLockedAndOnlyPlainIceCounts() {
        assertSame(ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET,
                ThermolithModeRules.ACTIVE_POLICY);
        assertFalse(ThermolithModeRules.ACTIVE_POLICY.togglesBackToNormal());
        assertFalse(ThermolithModeRules.ACTIVE_POLICY.acceptsAnyIce());
    }

    @Test
    void policyIceItemSetsAreExplicit() {
        assertFalse(ThermolithModeRules.InteractionPolicy.VANILLA_ICE_TOGGLE_RESET.acceptsAnyIce());
        assertTrue(ThermolithModeRules.InteractionPolicy.ANY_ICE_TOGGLE_RESET.acceptsAnyIce());
        assertFalse(ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET.acceptsAnyIce());
        assertFalse(ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET.togglesBackToNormal());
    }

    /** 可切换口径：同材料再点一次回 NORMAL，异材料直接换模式。 */
    @Test
    void togglePoliciesSwitchAndToggleBack() {
        for (var policy : List.of(ThermolithModeRules.InteractionPolicy.VANILLA_ICE_TOGGLE_RESET,
                ThermolithModeRules.InteractionPolicy.ANY_ICE_TOGGLE_RESET)) {
            assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.NORMAL, ThermolithMode.ICE, policy));
            assertEquals(ThermolithMode.NORMAL, ThermolithModeRules.nextMode(ThermolithMode.ICE, ThermolithMode.ICE, policy));
            assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.MAGMA, ThermolithMode.ICE, policy));
            assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.nextMode(ThermolithMode.NORMAL, ThermolithMode.MAGMA, policy));
            assertEquals(ThermolithMode.NORMAL, ThermolithModeRules.nextMode(ThermolithMode.MAGMA, ThermolithMode.MAGMA, policy));
            assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.nextMode(ThermolithMode.ICE, ThermolithMode.MAGMA, policy));
            // "无请求"不改变当前模式
            assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.ICE, ThermolithMode.NORMAL, policy));
        }
    }

    /** 只能拆方块复位的口径：右键只负责"设为该模式"，绝不回 NORMAL。 */
    @Test
    void breakOnlyPolicyNeverTogglesBack() {
        var policy = ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET;
        assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.NORMAL, ThermolithMode.ICE, policy));
        assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.ICE, ThermolithMode.ICE, policy));
        assertEquals(ThermolithMode.ICE, ThermolithModeRules.nextMode(ThermolithMode.MAGMA, ThermolithMode.ICE, policy));
        assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.nextMode(ThermolithMode.NORMAL, ThermolithMode.MAGMA, policy));
        assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.nextMode(ThermolithMode.MAGMA, ThermolithMode.MAGMA, policy));
        assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.nextMode(ThermolithMode.ICE, ThermolithMode.MAGMA, policy));
    }

    @Test
    void sneakResetOnlyWorksOnTogglePolicies() {
        assertEquals(ThermolithMode.NORMAL, ThermolithModeRules.resetMode(ThermolithMode.ICE,
                ThermolithModeRules.InteractionPolicy.VANILLA_ICE_TOGGLE_RESET));
        assertEquals(ThermolithMode.NORMAL, ThermolithModeRules.resetMode(ThermolithMode.MAGMA,
                ThermolithModeRules.InteractionPolicy.ANY_ICE_TOGGLE_RESET));
        assertEquals(ThermolithMode.NORMAL, ThermolithModeRules.resetMode(ThermolithMode.NORMAL,
                ThermolithModeRules.InteractionPolicy.VANILLA_ICE_TOGGLE_RESET));

        assertEquals(ThermolithMode.ICE, ThermolithModeRules.resetMode(ThermolithMode.ICE,
                ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET));
        assertEquals(ThermolithMode.MAGMA, ThermolithModeRules.resetMode(ThermolithMode.MAGMA,
                ThermolithModeRules.InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET));
    }

    // ---------------------------------------------------------------- 覆盖资源包

    /** 24 个变体键齐全：3 模式 × 2 通电 × 4 朝向，且朝向→旋转、模式→模型都对上。 */
    @Test
    void blockstateOverrideCoversAllTwentyFourVariants() {
        JsonObject root = readJson("/thermolith_override_pack/assets/cold_sweat/blockstates/thermolith.json");
        JsonObject variants = root.getAsJsonObject("variants");
        assertNotNull(variants, "blockstates/thermolith.json 缺 variants");

        Set<String> expected = new LinkedHashSet<>();
        for (String mode : List.of("normal", "ice", "magma")) {
            for (String powered : List.of("false", "true")) {
                for (String facing : List.of("north", "east", "south", "west")) {
                    expected.add("dimblend_mode=" + mode + ",powered=" + powered + ",facing=" + facing);
                }
            }
        }

        Set<String> actual = new LinkedHashSet<>(variants.keySet());
        assertEquals(expected, actual, "变体集合必须覆盖整个方块状态空间，多余/缺失都会让方块隐形");

        for (String key : expected) {
            JsonObject variant = variants.getAsJsonObject(key);
            String mode = key.split(",")[0].substring("dimblend_mode=".length());
            boolean powered = key.contains("powered=true");
            String facing = key.substring(key.indexOf("facing=") + "facing=".length());

            String prefix = switch (mode) {
                case "ice" -> "dimblend_experience:block/thermolith_ice_";
                case "magma" -> "dimblend_experience:block/thermolith_magma_";
                default -> "cold_sweat:block/thermolith_";
            };
            assertEquals(prefix + (powered ? "on" : "off"), variant.get("model").getAsString(), key);

            int y = switch (facing) {
                case "east" -> 90;
                case "south" -> 180;
                case "west" -> 270;
                default -> 0;
            };
            if (y == 0) {
                assertFalse(variant.has("y"), "north 不该带 y 旋转: " + key);
            } else {
                assertEquals(y, variant.get("y").getAsInt(), key);
            }
        }
    }

    /** 四个自定义模型：parent 指回 Cold Sweat 的原模型，只覆盖 four 个贴图槽位。 */
    @Test
    void customModelsOverrideOnlyTheFourTextureSlots() {
        for (String mode : List.of("ice", "magma")) {
            for (String state : List.of("off", "on")) {
                String path = "/assets/dimblend_experience/models/block/thermolith_" + mode + "_" + state + ".json";
                JsonObject model = readJson(path);
                assertEquals("cold_sweat:block/thermolith_" + state, model.get("parent").getAsString(), path);
                assertFalse(model.has("elements"), "元素继承 parent，模型里只放贴图覆盖: " + path);

                JsonObject textures = model.getAsJsonObject("textures");
                assertNotNull(textures, "缺 textures: " + path);
                assertEquals(Set.of("back", "top", "front", "head"), textures.keySet(), path);
                for (Map.Entry<String, com.google.gson.JsonElement> entry : textures.entrySet()) {
                    assertEquals("dimblend_experience:block/thermolith_" + entry.getKey() + "_" + state + "_" + mode,
                            entry.getValue().getAsString(), path);
                }
            }
        }
    }

    /** 模型引用的 16 张贴图真实存在，且 8 张熔岩贴图都带 .png.mcmeta（动态发光动画）。 */
    @Test
    void modelTexturesExistAndMagmaTexturesAreAnimated() {
        List<String> referenced = new ArrayList<>();
        for (String mode : List.of("ice", "magma")) {
            for (String state : List.of("off", "on")) {
                JsonObject textures = readJson("/assets/dimblend_experience/models/block/thermolith_" + mode + "_" + state
                        + ".json").getAsJsonObject("textures");
                for (var element : textures.entrySet()) {
                    referenced.add(element.getValue().getAsString());
                }
            }
        }
        assertEquals(16, referenced.size());

        for (String texture : referenced) {
            String[] parts = texture.split(":", 2);
            String namespace = parts[0];
            String pngPath = "/assets/" + namespace + "/textures/" + parts[1] + ".png";
            try (InputStream stream = getClass().getResourceAsStream(pngPath)) {
                assertNotNull(stream, "缺贴图 " + pngPath);
                assertTrue(stream.read() >= 0, "贴图是空的 " + pngPath);
            } catch (java.io.IOException failure) {
                throw new AssertionError("读取贴图失败 " + pngPath, failure);
            }

            boolean magma = parts[1].contains("_magma");
            String metaPath = pngPath + ".mcmeta";
            try (InputStream meta = getClass().getResourceAsStream(metaPath)) {
                if (magma) {
                    assertNotNull(meta, "熔岩贴图需带动画元数据 " + metaPath);
                    JsonObject animation = JsonParser.parseReader(
                            new InputStreamReader(meta, StandardCharsets.UTF_8)).getAsJsonObject();
                    assertTrue(animation.has("animation"), "mcmeta 里要有 animation 段: " + metaPath);
                } else {
                    assertNull(meta, "冰贴图是静态图，不该有 mcmeta: " + metaPath);
                }
            } catch (java.io.IOException failure) {
                throw new AssertionError("读取 mcmeta 失败 " + metaPath, failure);
            }
        }
    }

    /** 资源包声明自己是 1.21.1 客户端资源格式（34），否则会被整体判为不兼容。 */
    @Test
    void overridePackDeclaresClientResourceFormat() {
        JsonObject meta = readJson("/thermolith_override_pack/pack.mcmeta");
        assertEquals(34, meta.getAsJsonObject("pack").get("pack_format").getAsInt());
    }

    private JsonObject readJson(String resourcePath) {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, "找不到资源 " + resourcePath);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError("读取资源失败 " + resourcePath, failure);
        }
    }
}

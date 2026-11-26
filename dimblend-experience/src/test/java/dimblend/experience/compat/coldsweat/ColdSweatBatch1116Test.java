package dimblend.experience.compat.coldsweat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * 2026-11-16 批次：Cold Sweat 热源常数 + 恒温石模式/红石口径 + 覆盖资源包结构。
 *
 * <p>纯 JUnit：Cold Sweat / CDG 是 {@code compileOnly}，测试运行期不在 classpath 上，
 * 所以这里只碰不引用它们的部分（{@link ColdSweatHeatSources} 的常数与换算、
 * {@link ThermolithModeRules} 的纯规则），Cold Sweat 的
 * {@code Temperature.convert(20, F, MC, false) = 20/45} 这类口径以注释写死期望值。</p>
 *
 * <p>2026-11-27 批次追加：蒸汽引擎排气热源的<b>注册序源码契约</b>
 * （{@code EventPriority.LOWEST} + {@code BlockTempRegisterEvent#registerFirst}）与
 * {@link ColdSweatHeatSources#LAVA_EQUIVALENT} 对 {@code world.toml:242} {@code minecraft:lava}
 * 的参数一致性。注册序契约只能靠源码检查锁定——Cold Sweat 不在测试 classpath 上，
 * 而线上失败形态是"静默不生效"，行为测试无从触发。</p>
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

    // ------------------------------------------- 蒸汽引擎排气热源的注册序契约

    /**
     * 监听必须挂 {@link net.neoforged.bus.api.EventPriority#LOWEST}。
     *
     * <p>根因（{@code ColdSweat-2.4.2.jar} 字节码实证）：{@code BlockTempModifier#calculate}
     * 偏移 540–875 遍历 {@code BlockTempRegistry#getBlockTempsFor(state)} 时，遇到第一个
     * {@code isValid() == true && getTemperature() != 0.0} 的条目就累加后 {@code goto 875}
     * （跳出该方块的 BlockTemp 列表）——同一格后面的条目全部不执行。而
     * {@code create_cold_sweat-1.1.2.jar} 用默认 {@code NORMAL} 给 {@code create:steam_engine}
     * 注册了恒 {@code isValid} 且锅炉余温非零的 {@code SteamEngine}，且其 modId 字母序在本模组之前，
     * 所以本模组只有降到 {@code LOWEST} 才能排在它后面、把条目顶到下标 0。</p>
     */
    @Test
    void blockTempListenerUsesLowestPriority() throws IOException {
        String source = heatSourcesSource();

        assertTrue(source.contains("import net.neoforged.bus.api.EventPriority;"),
                "必须显式导入 EventPriority");

        String registerBody = slice(source,
                "public static void register()",
                "private static void onRegisterBlockTemps");
        assertTrue(registerBody.contains("addListener(EventPriority.LOWEST"),
                "BlockTempRegisterEvent 监听必须以 EventPriority.LOWEST 注册，"
                        + "否则字母序在前的 create_cold_sweat 抢先注册 SteamEngine");
        assertFalse(registerBody.contains("addListener(ColdSweatHeatSources::onRegisterBlockTemps)"),
                "两参 addListener 重载等于默认 NORMAL，禁止退回");
        // 幂等门控不能被顺手删掉
        assertTrue(registerBody.contains("isLoaded(\"cold_sweat\")"),
                "Cold Sweat 缺席时必须早退，否则静态初始化会拉缺席类");
    }

    /**
     * 蒸汽引擎热源必须用 {@code registerFirst}（{@code BlockTempRegistry#register(BlockTemp, true)}：
     * 条目插入 {@code BLOCK_TEMPS} 下标 0，并把 {@code MAPPED_BLOCKS.get(block)} 清空重排后插到下标 0），
     * 而不是 {@code register}。
     */
    @Test
    void steamEngineHeatSourceIsRegisteredFirst() throws IOException {
        String source = heatSourcesSource();

        String createRegister = slice(source,
                "private static final class Create",
                "private static boolean exhaustingEngine");
        assertTrue(createRegister.contains("event.registerFirst("),
                "create:steam_engine 的岩浆热源必须 registerFirst，否则被 create_cold_sweat 截胡");
        assertFalse(createRegister.contains("event.register("),
                "同一方块只能注册一条岩浆热源，不能退回 event.register(...)");
        assertTrue(createRegister.contains("lavaEquivalent("),
                "排气窗口施加的是 LAVA_EQUIVALENT（岩浆等价），不是烈焰人炉档位");
        assertTrue(createRegister.contains("Create::exhaustingEngine"),
                "判据必须是排气窗口，正常运转/停转的引擎不发热");
        // registerFirst 是全类唯一一处：其它方块（basic_burner / 炉灶 / CDG）仍走 register，
        // 因为 world.toml 与 create_cold_sweat 都不覆盖它们，没必要抢序。
        assertEquals(1, occurrences(source, "registerFirst("),
                "只有蒸汽引擎需要抢到下标 0");
    }

    /**
     * {@code LAVA_EQUIVALENT} 与 {@code world.toml:242} 的 {@code minecraft:lava} 同参：
     * {@code ["minecraft:lava", 30, 7, "f", 200, "", "", 1000, true]} ——
     * 温度 30 °F（相对）= 30/45 MC、range 7、maxEffect 200 °F = 200/45 MC、
     * 温度上限 1000 °F（绝对）= (1000−32)/45 MC、fade 恒 true（{@code BlockTempData.fromToml} 写死）、
     * 第九位 {@code true} = {@code logarithmic}。
     */
    @Test
    void lavaEquivalentProfileMatchesWorldTomlLava() {
        ColdSweatHeatSources.HeatProfile lava = ColdSweatHeatSources.LAVA_EQUIVALENT;

        assertEquals(30.0 / 45.0, lava.temperatureMc(), EPSILON);
        assertEquals(7.0, lava.range(), EPSILON);
        assertEquals(-200.0 / 45.0, lava.minEffectMc(), EPSILON);
        assertEquals(200.0 / 45.0, lava.maxEffectMc(), EPSILON);
        assertEquals(Double.NEGATIVE_INFINITY, lava.minTemperatureMc());
        assertEquals((1000.0 - 32.0) / 45.0, lava.maxTemperatureMc(), EPSILON);
        assertTrue(lava.fade(), "world.toml 的 BlockTempData.fromToml 恒传 fade=true");
        assertTrue(lava.logarithmic(), "minecraft:lava 第九位 true 即 logarithmic（对数衰减）");
    }

    /**
     * 若本机装着开发整合包，则直接拿 {@code config/coldsweat/world.toml} 第 242 行原值复算一遍，
     * 防止哪天整合包改了岩浆参数而这里悄悄漂移。CI / 干净环境没有该文件时跳过。
     */
    @Test
    void lavaEquivalentAgreesWithInstalledWorldTomlWhenAvailable() throws IOException {
        Path toml = Path.of("E:/misc/TrainTripWorld/config/coldsweat/world.toml");
        assumeTrue(Files.exists(toml), "本机没有开发整合包，跳过 world.toml 比对");

        String lavaRow = null;
        for (String line : Files.readAllLines(toml, StandardCharsets.UTF_8)) {
            if (line.contains("\"minecraft:lava\",")) {
                lavaRow = line;
                break;
            }
        }
        assertNotNull(lavaRow, "world.toml 里找不到 minecraft:lava 条目");

        int open = lavaRow.indexOf('[');
        int close = lavaRow.lastIndexOf(']');
        assertTrue(open >= 0 && close > open, "minecraft:lava 行格式异常: " + lavaRow);
        String[] cells = lavaRow.substring(open + 1, close).split(",");
        assertEquals(9, cells.length, "minecraft:lava 行应为 9 列: " + lavaRow);

        assertEquals("f", cells[3].trim().replace("\"", ""));
        ColdSweatHeatSources.HeatProfile lava = ColdSweatHeatSources.LAVA_EQUIVALENT;
        assertEquals(Double.parseDouble(cells[1].trim()) / 45.0, lava.temperatureMc(), EPSILON);
        assertEquals(Double.parseDouble(cells[2].trim()), lava.range(), EPSILON);
        assertEquals(Double.parseDouble(cells[4].trim()) / 45.0, lava.maxEffectMc(), EPSILON);
        assertEquals(-Double.parseDouble(cells[4].trim()) / 45.0, lava.minEffectMc(), EPSILON);
        assertEquals((Double.parseDouble(cells[7].trim()) - 32.0) / 45.0,
                lava.maxTemperatureMc(), EPSILON);
        assertEquals(Boolean.parseBoolean(cells[8].trim()), lava.logarithmic());
        assertTrue(lava.fade());
    }

    /** 模块内的主源码文本（不依赖 Cold Sweat 是否在 classpath 上）。 */
    private static String heatSourcesSource() throws IOException {
        Path relative = Path.of("src/main/java/dimblend/experience/compat/coldsweat/ColdSweatHeatSources.java");
        Path start = Path.of("").toAbsolutePath();
        // 从测试工作目录逐级向上找：模块根 / 仓库根 / build 目录下跑都能定位到。
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            Path inModule = dir.resolve("dimblend-experience").resolve(relative);
            if (Files.exists(inModule)) {
                return Files.readString(inModule, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("找不到 ColdSweatHeatSources.java（从 " + start + " 逐级向上都没找到）");
    }

    /** 取 {@code [fromMarker, toMarker)} 之间的源码片段，两端标记都必须存在。 */
    private static String slice(String source, String fromMarker, String toMarker) {
        int from = source.indexOf(fromMarker);
        assertTrue(from >= 0, "源码里找不到起始标记: " + fromMarker);
        int to = source.indexOf(toMarker, from + fromMarker.length());
        assertTrue(to > from, "源码里找不到结束标记: " + toMarker);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }

    // ---------------------------------------------------------------- 模式信号

    /** Celsius → MC 与 Cold Sweat 的 C→MC 分支一致（÷25）。 */
    @Test
    void celsiusToMcMatchesThresholds() {
        assertEquals(1.2, ThermolithModeRules.celsiusToMc(30.0), EPSILON);
        assertEquals(0.4, ThermolithModeRules.celsiusToMc(10.0), EPSILON);
        assertEquals(ThermolithModeRules.ICE_THRESHOLD_MC, ThermolithModeRules.celsiusToMc(30.0), EPSILON);
        assertEquals(ThermolithModeRules.MAGMA_THRESHOLD_MC, ThermolithModeRules.celsiusToMc(10.0), EPSILON);
        assertEquals(15, ThermolithModeRules.FULL_SIGNAL);
    }

    /** 冰模式：严格高于 30 °C（1.2 MC）才满 15；正好 30 °C 不输出。 */
    @Test
    void iceModeSignalsOnlyAboveThirtyCelsius() {
        double threshold = ThermolithModeRules.celsiusToMc(30.0);
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, threshold));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.ICE, threshold - 1.0E-9));
        assertEquals(15, ThermolithModeRules.signalFor(ThermolithMode.ICE, ThermolithModeRules.celsiusToMc(30.1)));
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

    /**
     * NORMAL 走原版线性插值（{@code ThermolithModeRules#tick} 里复刻同一公式），
     * {@link ThermolithModeRules#signalFor} 本身永远不参与 NORMAL 的模式信号。
     */
    @Test
    void normalModeNeverDrivesTheModeSignal() {
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, -100.0));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 0.0));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 1.2));
        assertEquals(0, ThermolithModeRules.signalFor(ThermolithMode.NORMAL, 100.0));
    }

    // ---------------------------------------------------------------- 红石抑制

    /** 输出侧红石粉回放的是温石自己的 15：整侧忽略，不构成抑制。 */
    @Test
    void suppressionIgnoresTheRedstoneWireOnItsOwnOutputFace() {
        int[] signals = new int[Direction.values().length];
        signals[Direction.NORTH.get3DDataValue()] = 15;
        assertFalse(ThermolithModeRules.isSuppressed(signals, Direction.NORTH, true));
    }

    /** 输出侧不是红石粉时（红石块/火把/拉杆/朝内中继器）照样算外源。 */
    @Test
    void suppressionHonoursNonWireSignalOnTheOutputFace() {
        int[] signals = new int[Direction.values().length];
        signals[Direction.NORTH.get3DDataValue()] = 15;
        assertTrue(ThermolithModeRules.isSuppressed(signals, Direction.NORTH, false));
    }

    /** 其余五面任意一面有信号都抑制，包括正下方与正上方。 */
    @Test
    void suppressionReadsAllFiveNonOutputFaces() {
        for (Direction direction : Direction.values()) {
            if (direction == Direction.NORTH) {
                continue;
            }
            int[] signals = new int[Direction.values().length];
            signals[direction.get3DDataValue()] = 1;
            assertTrue(ThermolithModeRules.isSuppressed(signals, Direction.NORTH, true), direction.name());
        }
    }

    @Test
    void noSignalMeansNoSuppression() {
        int[] signals = new int[Direction.values().length];
        assertFalse(ThermolithModeRules.isSuppressed(signals, Direction.NORTH, true));
        assertFalse(ThermolithModeRules.isSuppressed(signals, Direction.NORTH, false));
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

    /**
     * 48 个变体键齐全：3 模式 × 2 通电 × 4 朝向 × 2 抑制，且朝向→旋转、模式→模型都对上；
     * 抑制态一律用未通电（{@code off}）模型。
     */
    @Test
    void blockstateOverrideCoversAllFortyEightVariants() {
        JsonObject root = readJson("/thermolith_override_pack/assets/cold_sweat/blockstates/thermolith.json");
        JsonObject variants = root.getAsJsonObject("variants");
        assertNotNull(variants, "blockstates/thermolith.json 缺 variants");

        Set<String> expected = new LinkedHashSet<>();
        for (String mode : List.of("normal", "ice", "magma")) {
            for (String powered : List.of("false", "true")) {
                for (String facing : List.of("north", "east", "south", "west")) {
                    for (String suppressed : List.of("false", "true")) {
                        expected.add("dimblend_mode=" + mode + ",powered=" + powered
                                + ",facing=" + facing + ",dimblend_suppressed=" + suppressed);
                    }
                }
            }
        }

        Set<String> actual = new LinkedHashSet<>(variants.keySet());
        assertEquals(expected, actual, "变体集合必须覆盖整个方块状态空间，多余/缺失都会让方块隐形");

        for (String key : expected) {
            JsonObject variant = variants.getAsJsonObject(key);
            String mode = key.split(",")[0].substring("dimblend_mode=".length());
            boolean powered = key.contains("powered=true");
            boolean suppressed = key.endsWith("dimblend_suppressed=true");
            String facing = key.split(",")[2].substring("facing=".length());

            String prefix = switch (mode) {
                case "ice" -> "dimblend_experience:block/thermolith_ice_";
                case "magma" -> "dimblend_experience:block/thermolith_magma_";
                default -> "cold_sweat:block/thermolith_";
            };
            boolean lit = powered && !suppressed;
            assertEquals(prefix + (lit ? "on" : "off"), variant.get("model").getAsString(), key);

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

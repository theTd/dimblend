package dimblend.experience.compat.gunsmith;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 枪匠等级 tooltip mixin 的<b>注册契约</b>。
 *
 * <p>Mixin 源文件存在但没登记进 {@code dimblend_experience.mixins.json}（或没在
 * {@code DimBlendMixinPlugin} 里门控）时，Mixin 是**静默不加载**的：游戏照常启动、单测照常绿，
 * 但 tooltip 又变回 TaCZ 的 {@code "0 (MAX)"}——正是本 slice 要修的 bug。所以这里把两条登记
 * 都钉成断言（对齐仓库既有的分片审计习惯，见 {@code VoidFitSaveAndLavaScanTest}、
 * {@code MixinHygieneContractTest}）。</p>
 *
 * <p><b>如果你看到这个测试红</b>：说明共享文件（`dimblend_experience.mixins.json` /
 * `DimBlendMixinPlugin.java`）里还缺 `compat.gunsmith.GunsmithGunLevelTooltipMixin` 的两条登记，
 * 精确补丁写在 `.work-notes/slice-3.md` 的「需要统筹者合入共享文件」一节。</p>
 */
class GunsmithTooltipRegistrationTest {

    private static final String MIXIN_SIMPLE_NAME = "GunsmithGunLevelTooltipMixin";
    private static final String MIXIN_FQCN = "compat.gunsmith." + MIXIN_SIMPLE_NAME;

    @Test
    void tooltipMixinIsRegisteredInClientMixinList() throws IOException {
        String json = read("src/main/resources/dimblend_experience.mixins.json");
        assertTrue(json.contains('"' + MIXIN_FQCN + '"'),
                "dimblend_experience.mixins.json 的 \"client\" 列表必须包含 \"" + MIXIN_FQCN
                        + "\"（否则 mixin 静默不加载，tooltip 仍是 0 (MAX)）");

        // 必须落在 client 列表里（目标是仅客户端的 com.tacz.guns.client.tooltip.ClientGunTooltip）
        int clientListStart = json.indexOf("\"client\"");
        assertTrue(clientListStart >= 0, "mixins.json 缺少 \"client\" 列表");
        int entryIndex = json.indexOf('"' + MIXIN_FQCN + '"');
        int clientEnd = clientListEnd(json, clientListStart);
        assertTrue(entryIndex > clientListStart && entryIndex < clientEnd,
                MIXIN_FQCN + " 必须登记在 \"client\" 列表内（服务端不得加载客户端类 mixin）");
    }

    @Test
    void tooltipMixinIsGatedInPlugin() throws IOException {
        String plugin = read("src/main/java/dimblend/experience/mixin/DimBlendMixinPlugin.java");
        assertTrue(plugin.contains(MIXIN_SIMPLE_NAME),
                "DimBlendMixinPlugin 必须为 " + MIXIN_SIMPLE_NAME
                        + " 加门控（tacz+gundb 存在 + classPresent(\"com.tacz.guns.client.tooltip.ClientGunTooltip\")）");
        assertTrue(plugin.contains("com.tacz.guns.client.tooltip.ClientGunTooltip"),
                "门控必须用 classPresent 检查客户端目标类是否存在，避免版本不含该类时启动即崩");
    }

    @Test
    void tooltipMixinStillTargetsTaczClientTooltipAndRewritesTheLevelLine() throws IOException {
        String source = read("src/main/java/dimblend/experience/mixin/compat/gunsmith/"
                + MIXIN_SIMPLE_NAME + ".java");
        assertTrue(source.contains("com.tacz.guns.client.tooltip.ClientGunTooltip"),
                "目标类必须是 TaCZ 的 ClientGunTooltip");
        assertTrue(source.contains("getText()V") && source.contains("\"TAIL\""),
                "必须在 ClientGunTooltip#getText 的 TAIL 覆写等级行（构造期调用一次，此时 levelInfo 已算好）");
        assertTrue(source.contains("GunsmithLevelText.levelText"),
                "等级文本必须走 GunsmithLevelText（0/49 不显示 MAX，50 显示 50 (MAX) 的唯一口径）");
        assertTrue(source.contains("require = 1") && source.contains("allow = 1"),
                "注入必须钉死 require/allow = 1（字节码变了要立刻炸，而不是静默失效）");
        // 客户端 tooltip 绝不能读 Config：等级只能来自随 ItemStack 同步的 custom_data
        assertTrue(!source.contains("Config."),
                "tooltip 绝不允许读 Config（客户端会拿到未同步的默认值），等级只能从 CUSTOM_DATA 读");
    }

    private static int clientListEnd(String json, int clientListStart) {
        int open = json.indexOf('[', clientListStart);
        assertTrue(open >= 0, "\"client\" 列表格式异常");
        int depth = 0;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return json.length();
    }

    private static String read(String relativePath) throws IOException {
        Path moduleRoot = Path.of("").toAbsolutePath();
        List<Path> candidates = List.of(
                moduleRoot.resolve(relativePath),
                moduleRoot.resolve("dimblend-experience").resolve(relativePath));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not locate " + relativePath + " from " + moduleRoot);
    }
}

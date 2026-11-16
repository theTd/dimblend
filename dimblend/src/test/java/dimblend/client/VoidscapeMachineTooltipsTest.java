package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Voidscape 8 台机器说明文案的自检：ID → 文案键的映射必须齐全，
 * 且 {@code assets/dimblend/lang} 的中英文值逐条精确（用户逐条确认的口径）。
 */
class VoidscapeMachineTooltipsTest {

    private static final Map<ResourceLocation, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(machine("liquifier"), "tooltip.dimblend.voidscape_liquifier");
        EXPECTED.put(machine("defuser"), "tooltip.dimblend.voidscape_defuser");
        EXPECTED.put(machine("germinator"), "tooltip.dimblend.voidscape_germinator");
        EXPECTED.put(machine("well"), "tooltip.dimblend.voidscape_well");
        EXPECTED.put(machine("coop"), "tooltip.dimblend.voidscape_coop");
        EXPECTED.put(machine("hatchery"), "tooltip.dimblend.voidscape_hatchery");
        EXPECTED.put(machine("infuser"), "tooltip.dimblend.voidscape_infuser");
        EXPECTED.put(machine("collector"), "tooltip.dimblend.voidscape_collector");
    }

    @Test
    void everyVoidscapeMachineMapsToItsOwnTranslationKey() {
        assertEquals(8, VoidscapeMachineTooltips.tooltipKeys().size(), "Voidscape 机器必须恰好 8 台");
        assertEquals(EXPECTED, VoidscapeMachineTooltips.tooltipKeys(), "机器 ID 与文案键映射不符");
    }

    @Test
    void chineseTranslationsAreExact() throws Exception {
        JsonObject lang = lang("zh_cn");
        assertEquals("反向结晶", lang.get(EXPECTED.get(machine("liquifier"))).getAsString());
        assertEquals("防护虚空侵蚀", lang.get(EXPECTED.get(machine("defuser"))).getAsString());
        assertEquals("高效作物催熟", lang.get(EXPECTED.get(machine("germinator"))).getAsString());
        assertEquals("\"很好的\"虚空水井", lang.get(EXPECTED.get(machine("well"))).getAsString());
        assertEquals("像鸡蛋就行 不要细想", lang.get(EXPECTED.get(machine("coop"))).getAsString());
        assertEquals("…………", lang.get(EXPECTED.get(machine("hatchery"))).getAsString());
        assertEquals("这家伙 对世界上所有亡灵生物都有额外的恨意",
                lang.get(EXPECTED.get(machine("infuser"))).getAsString());
        assertEquals("不知为什么 这个东西 可复制其正下方的矿岩",
                lang.get(EXPECTED.get(machine("collector"))).getAsString());
    }

    @Test
    void englishTranslationsAreExact() throws Exception {
        JsonObject lang = lang("en_us");
        assertEquals("Reverse crystallization", lang.get(EXPECTED.get(machine("liquifier"))).getAsString());
        assertEquals("Shields against Void corrosion", lang.get(EXPECTED.get(machine("defuser"))).getAsString());
        assertEquals("High-efficiency crop ripening", lang.get(EXPECTED.get(machine("germinator"))).getAsString());
        assertEquals("A \"perfectly fine\" Void water well", lang.get(EXPECTED.get(machine("well"))).getAsString());
        assertEquals("Looks enough like an egg — don't overthink it",
                lang.get(EXPECTED.get(machine("coop"))).getAsString());
        assertEquals("............", lang.get(EXPECTED.get(machine("hatchery"))).getAsString());
        assertEquals("This thing harbors extra malice toward all undead in the world",
                lang.get(EXPECTED.get(machine("infuser"))).getAsString());
        assertEquals("For some reason, this thing duplicates stone and mineral rock right beneath it",
                lang.get(EXPECTED.get(machine("collector"))).getAsString());
    }

    private static ResourceLocation machine(String name) {
        return ResourceLocation.fromNamespaceAndPath("voidscape", "machine_" + name);
    }

    private static JsonObject lang(String code) throws Exception {
        try (InputStream in = VoidscapeMachineTooltipsTest.class
                .getResourceAsStream("/assets/dimblend/lang/" + code + ".json")) {
            assertNotNull(in, "缺少语言文件 " + code);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        }
    }
}

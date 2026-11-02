package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpyglassTooltipLangTest {
    private static final String KEY = "dimblend_spyglass.tooltip.spyglass_hint";
    private static final String EN_US = "/assets/dimblend_spyglass/lang/en_us.json";
    private static final String ZH_CN = "/assets/dimblend_spyglass/lang/zh_cn.json";

    @Test
    void bothLanguagesTranslateTheHintAndTheChineseIsNotTheEnglish() throws IOException {
        JsonObject english = json(EN_US);
        JsonObject chinese = json(ZH_CN);

        assertFalse(text(english, KEY).isBlank(), "en_us is missing " + KEY);
        assertFalse(text(chinese, KEY).isBlank(), "zh_cn is missing " + KEY);
        assertNotEquals(text(english, KEY), text(chinese, KEY), "zh_cn copies the English hint");
    }

    private static String text(JsonObject lang, String key) {
        return lang.has(key) ? lang.get(key).getAsString() : "";
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream stream = SpyglassTooltipLangTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}

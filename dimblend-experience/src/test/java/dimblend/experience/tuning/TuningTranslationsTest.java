package dimblend.experience.tuning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TuningTranslationsTest {
    @Test
    void bothLanguagesCoverEveryPanelSettingAndStatus() throws Exception {
        JsonObject english = language("en_us");
        JsonObject chinese = language("zh_cn");
        assertEquals(english.keySet(), chinese.keySet());
        for (TuningOption option : TuningOption.values()) {
            for (JsonObject language : new JsonObject[] {english, chinese}) {
                assertFalse(language.get(option.key()).getAsString().isBlank());
                assertFalse(language.get(option.key() + ".description").getAsString().isBlank());
            }
        }
        for (String status : new String[] {"no_permission", "invalid", "unavailable", "saving", "saved",
                "save_failed", "timeout", "increase", "decrease", "reset_default", "missing_mod"}) {
            assertTrue(english.has(TuningOption.PREFIX + status), status);
        }
    }

    private JsonObject language(String code) throws Exception {
        var stream = getClass().getResourceAsStream("/assets/dimblend_experience/lang/" + code + ".json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

package dimblend.experience.tuning.perf;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerfChecklistTranslationsTest {
    @Test
    void bothLanguagesCoverEveryStepAdviceFixAndLabel() throws Exception {
        List<String> keys = new ArrayList<>();
        for (PerfCheck check : PerfCheck.values()) {
            keys.add(check.key());
            keys.add(check.key() + ".why");
            keys.add(check.key() + ".current");
            check.advices().forEach(advice -> keys.add(check.key() + ".advice." + advice));
            if (check.fixable()) {
                keys.add(check.key() + ".fix");
            }
        }
        for (PerfStatus status : PerfStatus.values()) {
            keys.add(status.key());
        }
        for (PerfScope scope : PerfScope.values()) {
            keys.add(scope.key());
        }
        for (String suffix : new String[] {"title", "step", "summary", "summary_intro", "label.current",
                "label.advice", "label.why", "recheck", "back", "next", "to_summary", "done", "open_sable",
                "message.checking", "message.applying", "message.fixed", "message.fix_failed",
                "message.fix_unavailable", "message.no_server"}) {
            keys.add(PerfCheck.PREFIX + suffix);
        }
        keys.add("screen.dimblend_experience.tuning.section_perf");
        for (String code : new String[] {"en_us", "zh_cn"}) {
            JsonObject language = language(code);
            for (String key : keys) {
                assertTrue(language.has(key) && !language.get(key).getAsString().isBlank(), code + " " + key);
            }
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

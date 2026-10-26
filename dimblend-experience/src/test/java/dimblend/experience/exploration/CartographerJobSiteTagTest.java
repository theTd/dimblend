package dimblend.experience.exploration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A13 制图台不再是村民工作站的资源接线校验：本模组以 NeoForge 的 tag remove 补丁
 * 从 {@code #minecraft:acquirable_job_site} 里摘掉 {@code minecraft:cartographer}
 * （NeoForge 21.1 {@code TagLoader} 支持 {@code remove} 段）。纯资源校验：
 * 不加载任何 Minecraft 注册表，只断言 JSON 语义——不能被误改成整表替换
 * （会把其他模组的工作站一并洗掉），也不能顺手删掉别的 POI。
 */
class CartographerJobSiteTagTest {

    private static final String TAG_RESOURCE =
            "/data/minecraft/tags/point_of_interest_type/acquirable_job_site.json";

    @Test
    void acquirableJobSiteTagRemovesCartographerOnly() throws Exception {
        JsonObject tag = loadTag();
        assertFalse(tag.has("replace") && tag.get("replace").getAsBoolean(),
                "the tag must stay additive; a full replace would wipe modded workstations");

        JsonArray values = tag.getAsJsonArray("values");
        assertNotNull(values, "values is a required tag field");
        assertEquals(0, values.size(), "values must stay empty: this file only removes an entry");

        JsonArray remove = tag.getAsJsonArray("remove");
        assertNotNull(remove, "the file must use the NeoForge remove section");
        assertEquals(Set.of("minecraft:cartographer"), entryIds(remove),
                "exactly minecraft:cartographer must be removed from the job-site tag");
    }

    private static Set<String> entryIds(JsonArray entries) {
        Set<String> ids = new HashSet<>();
        for (JsonElement value : entries) {
            ids.add(value.isJsonPrimitive() ? value.getAsString() : value.getAsJsonObject().get("id").getAsString());
        }
        return ids;
    }

    private static JsonObject loadTag() throws Exception {
        InputStream stream = CartographerJobSiteTagTest.class.getResourceAsStream(TAG_RESOURCE);
        assertNotNull(stream, "tag file missing: " + TAG_RESOURCE);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}

package dimblend.experience.compat.create.observer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-05：观察者烈焰人的坐垫配色由红改橙。
 *
 * <p>坐垫不是 BER 画的，是普通方块模型 + Create 预先上色的贴图，所以"换颜色"只是模型里
 * 三个材质槽换一个 id，没有任何染色/tint 逻辑。Create 6.0.10 自带 16 套预上色座椅贴图
 * （{@code create:block/seat/top_<colour>} / {@code side_<colour>}），橙色就是其中一套，
 * 因此这里不需要自造贴图，也不允许出现"自己调色"的实现。
 *
 * <p>本测试读的是打进 jar 的真实资源文件，不是复制品：模型文件是该坐垫外观的唯一来源。
 */
class ObserverBlazeCushionTest {

    private static final String MODEL_RESOURCE =
            "/assets/dimblend_experience/models/block/observer_blaze.json";

    /** 本次任务的唯一改动：三个材质槽指向 Create 的橙色座椅贴图。 */
    @Test
    void cushionUsesTheOrangeSeatTextures() throws Exception {
        JsonObject textures = loadModelTextures();

        assertEquals("create:block/seat/top_orange", texture(textures, "1"),
                "坐垫顶面必须是 Create 的橙色座椅顶面贴图");
        assertEquals("create:block/seat/side_orange", texture(textures, "2"),
                "坐垫四个侧面必须是 Create 的橙色座椅侧面贴图");
        assertEquals("create:block/seat/top_orange", texture(textures, "particle"),
                "破坏粒子的贴图槽必须跟着顶面走，否则碎屑仍然是红色的");
    }

    /**
     * 底面不在本次改动范围内：它是 Create 的中性座椅底面，与颜色无关。
     * 顺手钉住它，防止"全部换成橙色"的粗改把底面也改掉。
     */
    @Test
    void cushionUndersideStaysTheColourNeutralSeatBottom() throws Exception {
        assertEquals("create:block/seat/bottom", texture(loadModelTextures(), "0"),
                "坐垫底面是 Create 的中性底面贴图，不应随颜色变化");
    }

    /**
     * 任何一处残留的 {@code _red} 引用都会让坐垫（或它的破坏粒子）红一块橙一块，
     * 而挨个材质槽断言很容易漏掉一个槽，所以这里对整份文件做一次全文检查。
     */
    @Test
    void noRedSeatTextureReferenceRemains() throws Exception {
        String raw = readModel();
        assertTrue(!raw.contains("_red"),
                "模型里不允许再残留任何 _red 贴图引用，实际内容：" + raw);
    }

    /**
     * 用户这次明确只换颜色、不动高度。三个高度常量是链式求值的
     * （{@code SPYGLASS_Y} 与 {@code FLAME_BASE_Y} 都由 {@code BLAZE_RAISE} 推导），
     * 所以改动 {@code BLAZE_RAISE} 会悄悄把望远镜和火苗一起挪走。
     * 把现行数值钉死在这里，将来手滑改高度会直接在这里变红。
     */
    @Test
    void verticalLayoutConstantsAreUnchanged() {
        assertEquals(0.125F, ObserverBlazeLayout.BLAZE_RAISE, 1.0E-4F,
                "BLAZE_RAISE 必须保持 0.125：本次只换坐垫颜色，不动高度");
        assertEquals(1.5F, ObserverBlazeLayout.SPYGLASS_Y, 1.0E-4F,
                "SPYGLASS_Y 必须保持 1.5：本次只换坐垫颜色，不动高度");
        assertEquals(1.075F, ObserverBlazeLayout.FLAME_BASE_Y, 1.0E-4F,
                "FLAME_BASE_Y 必须保持 1.075：本次只换坐垫颜色，不动高度");
    }

    private static JsonObject loadModelTextures() throws Exception {
        JsonObject model = JsonParser.parseString(readModel()).getAsJsonObject();
        assertNotNull(model.getAsJsonObject("textures"),
                "模型缺少 textures 段：" + model);
        return model.getAsJsonObject("textures");
    }

    private static String texture(JsonObject textures, String slot) {
        assertTrue(textures.has(slot), "模型缺少材质槽 \"" + slot + "\"：" + textures);
        return textures.get(slot).getAsString();
    }

    private static String readModel() throws Exception {
        InputStream stream = ObserverBlazeCushionTest.class.getResourceAsStream(MODEL_RESOURCE);
        assertNotNull(stream, "缺少资源 " + MODEL_RESOURCE);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            StringBuilder raw = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                raw.append(buffer, 0, read);
            }
            return raw.toString();
        }
    }
}
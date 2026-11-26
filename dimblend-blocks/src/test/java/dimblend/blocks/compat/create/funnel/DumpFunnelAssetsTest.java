package dimblend.blocks.compat.create.funnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 卸料漏斗资产"对齐 Create 6.0.10 黄铜漏斗"的机械自检。
 *
 * <p>三态的视觉问题一旦定位到资产层，只有四种可能：blockstate 的变体/旋转写错、模型没继承
 * 原版几何（自带 elements 或 parent 挂错）、父模型用到的贴图槽位没被覆盖（模型会回落到
 * Create 的黄铜贴图）、或者方块被按错误的渲染层绘制（透明像素被当不透明画出来）。这四件事都能
 * 在纯 JVM 里断言，不需要启动游戏：</p>
 * <ul>
 *   <li>Create 6.0.10 的 jar（{@code com.simibubi.create:create-1.21.1:6.0.10-281}）就在
 *       测试类路径上，它的 {@code assets/create/**} 与被测的 {@code assets/dimblend_blocks/**}
 *       可以同时读到，于是可以逐个变体、逐张 PNG 比对；</li>
 *   <li>父模型链的元素引用 {@code #槽位名}，可以递归收集后与本地 {@code textures} 求差集。</li>
 * </ul>
 *
 * <p><b>渲染层为什么必须写进模型 JSON：</b>Create 在 {@code AllBlocks} 里给
 * {@code BRASS_FUNNEL} / {@code BRASS_BELT_FUNNEL} 注册了 {@code RenderType.cutoutMipped()}，
 * 我们这两个方块既没有代码侧注册，就必须在模型 JSON 里声明 {@code "render_type"}——否则
 * {@code RenderTypeGroup.EMPTY} 会让分块渲染层退化成 {@code solid}，贴图里 {@code alpha == 0}
 * 的镂空/边框/箭头全被画成实心面（实测就是"传送带入口被亮蓝色块盖死"）。</p>
 *
 * <p>NeoForge 21.1.249 的读法可以逐层核对：{@code ExtendedBlockModelDeserializer#deserialize}
 * 把 {@code render_type} 写进 {@code model.customData.setRenderTypeHint} →
 * {@code UnbakedGeometryHelper#bake} 走 {@code ElementsModel#bake(blockModel.customData, ...)} →
 * {@code SimpleUnbakedGeometry#bake} 取 {@code context.getRenderTypeHint()} →
 * {@code NamedRenderTypeManager} 里 {@code minecraft:cutout_mipped} 预注册为
 * {@code new RenderTypeGroup(RenderType.cutoutMipped(), ITEM_LAYERED_CUTOUT)}，与 Create 代码侧
 * 注册的 {@code cutoutMipped()} 完全同层；不写则为 {@code ChunkRenderTypeSet.of(solid())}。</p>
 *
 * <p><b>物品模型刻意不写 {@code render_type}（与 Create 的 {@code block/funnel/item} 一致）：</b>
 * 物品路径本来就走 {@code RenderTypeHelper#getFallbackItemRenderType}
 * （{@code DumpFunnelItem extends FunnelItem extends BlockItem} ⇒ {@code Sheets.cutoutBlockSheet()}），
 * 已经带 alpha test；一旦给它写上 hint，{@code SimpleBakedModel#getRenderTypes(ItemStack, boolean)}
 * 就会改返回该组的 item 层（{@code neoforge_item_entity_cutout}），是纯风险无收益的改动。</p>
 */
class DumpFunnelAssetsTest {

    private static final String OUR_BLOCKSTATES = "assets/dimblend_blocks/blockstates/";
    private static final String OUR_MODELS = "assets/dimblend_blocks/models/block/funnel/";
    private static final String OUR_TEXTURES = "assets/dimblend_blocks/textures/block/funnel/";

    private static final String ITEM_MODEL = "dump_funnel_item";
    private static final String CUTOUT_MIPPED = "minecraft:cutout_mipped";

    /** 我们的模型名 -> 必须继承的 Create 原版模型。 */
    private static final Map<String, String> EXPECTED_PARENTS = expectedParents();

    /** 我们的贴图 -> Create 原版同源贴图（重着色前的原图），alpha 掩码必须逐像素一致。 */
    private static final Map<String, String> TEXTURE_SOURCES = textureSources();

    /** 唯一合法"全不透明"的贴图：它对应 Create 的 brass_block，原图本来就没有镂空。 */
    private static final Set<String> NO_CUTOUT_TEXTURES = Set.of("dump_funnel_block.png");

    private static Map<String, String> expectedParents() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String shape : new String[] {"retracted", "extended", "pulling", "pushing"}) {
            for (String power : new String[] {"powered", "unpowered"}) {
                map.put("dump_belt_funnel_" + shape + "_" + power, "create:block/belt_funnel/block_" + shape);
            }
        }
        for (String direction : new String[] {"pull", "push"}) {
            for (String power : new String[] {"powered", "unpowered"}) {
                map.put("dump_funnel_horizontal_" + direction + "_" + power, "create:block/funnel/block_horizontal");
                map.put("dump_funnel_vertical_" + direction + "_" + power, "create:block/funnel/block_vertical");
            }
        }
        map.put("dump_funnel_item", "create:block/funnel/item");
        return map;
    }

    private static Map<String, String> textureSources() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("dump_funnel.png", "assets/create/textures/block/funnel/brass_funnel.png");
        map.put("dump_funnel_block.png", "assets/create/textures/block/brass_block.png");
        map.put("dump_funnel_neutral.png", "assets/create/textures/block/funnel/brass_funnel_neutral.png");
        map.put("dump_funnel_pull.png", "assets/create/textures/block/funnel/brass_funnel_pull.png");
        map.put("dump_funnel_push.png", "assets/create/textures/block/funnel/brass_funnel_push.png");
        map.put("dump_funnel_powered.png", "assets/create/textures/block/funnel/brass_funnel_powered.png");
        map.put("dump_funnel_unpowered.png", "assets/create/textures/block/funnel/brass_funnel_unpowered.png");
        map.put("dump_funnel_back.png", "assets/create/textures/block/funnel/funnel_back.png");
        map.put("dump_funnel_frame.png", "assets/create/textures/block/funnel/brass_funnel_frame.png");
        return map;
    }

    // ------------------------------------------------------------ blockstate 变体

    @Test
    void funnelBlockstateMirrorsCreateBrassFunnelVariants() {
        assertVariantsMatch(OUR_BLOCKSTATES + "dump_funnel.json",
                "dimblend_blocks:block/funnel/dump_funnel_", "assets/create/blockstates/brass_funnel.json",
                "create:block/brass_funnel_", 48);
    }

    @Test
    void beltFunnelBlockstateMirrorsCreateBrassBeltFunnelVariants() {
        assertVariantsMatch(OUR_BLOCKSTATES + "dump_belt_funnel.json",
                "dimblend_blocks:block/funnel/dump_belt_funnel_", "assets/create/blockstates/brass_belt_funnel.json",
                "create:block/brass_belt_funnel_", 64);
    }

    // ------------------------------------------------------------ 渲染层

    /**
     * 16 个方块模型必须全部声明 {@code cutout_mipped}。
     *
     * <p>这是本次"亮蓝色实心块盖死传送带入口"的唯一根因修复点：Create 用的是代码侧
     * {@code RenderType.cutoutMipped()}，我们只能走模型 JSON 的同名声明。少一个变体没写，那个
     * 变体就会退回 {@code solid}；漏写任何一个都会在实机三态里露馅，所以这里按模型名单逐一断言，
     * 且把数量钉成 16 防止以后新增变体时忘了补。</p>
     *
     * <p>{@code dump_funnel_item} 是物品模型，刻意不在名单里（理由见类注释）。</p>
     */
    @Test
    void allSixteenBlockModelsDeclareCutoutMippedRenderType() {
        List<String> blockModels = new ArrayList<>();
        for (String name : EXPECTED_PARENTS.keySet()) {
            if (!ITEM_MODEL.equals(name)) {
                blockModels.add(name);
            }
        }
        assertEquals(16, blockModels.size(),
                "卸料漏斗方块模型应为 16 个（8 belt + 4 horizontal + 4 vertical）：" + blockModels);

        List<String> problems = new ArrayList<>();
        for (String name : blockModels) {
            String file = OUR_MODELS + name + ".json";
            JsonObject model = readJson(file);
            if (!model.has("render_type")) {
                problems.add(name + " 缺 render_type（会退回 solid，透明像素糊成实心面）");
                continue;
            }
            String declared = model.get("render_type").getAsString();
            if (!CUTOUT_MIPPED.equals(declared)) {
                problems.add(name + " 的 render_type=" + declared + "，应为 " + CUTOUT_MIPPED);
            }
        }
        assertTrue(problems.isEmpty(), "渲染层声明不完整:\n" + String.join("\n", problems));
    }

    /**
     * 物品模型不写 {@code render_type}（与 Create 的 {@code block/funnel/item} 一致）。
     *
     * <p>物品不是靠 hint 决定透明度的：{@code DumpFunnelItem extends FunnelItem extends BlockItem}，
     * 物品渲染走 {@code RenderTypeHelper#getFallbackItemRenderType} → {@code Sheets.cutoutBlockSheet()}，
     * 本来就是 alpha test 的 cutout 层。写了 hint 只会让
     * {@code SimpleBakedModel#getRenderTypes(ItemStack, boolean)} 改返回该组的 item 层
     * （{@code neoforge_item_entity_cutout}），对图标没有任何视觉收益，却把一条本来正常的渲染路径
     * 换掉。这里断言"没写"，是为了防止后人照着方块模型的写法顺手补上一行。</p>
     */
    @Test
    void itemModelDeliberatelyKeepsNoRenderType() {
        JsonObject itemBlockModel = readJson(OUR_MODELS + ITEM_MODEL + ".json");
        assertFalse(itemBlockModel.has("render_type"),
                "dump_funnel_item.json 不该声明 render_type：物品已是 cutout，加了只会换掉物品渲染层（见类注释）");
    }

    // ------------------------------------------------------------ 模型继承

    @Test
    void blockModelsArePureTextureOverridesOfCreateGeometry() {
        for (Map.Entry<String, String> entry : EXPECTED_PARENTS.entrySet()) {
            String file = OUR_MODELS + entry.getKey() + ".json";
            JsonObject model = readJson(file);

            assertEquals(entry.getValue(), model.get("parent").getAsString(),
                    file + " 必须直接继承 Create 原版模型");
            assertFalse(model.has("elements"),
                    file + " 不允许自带几何：三态形状必须 100% 来自 Create 原版模型");
            assertTrue(model.has("textures"), file + " 必须覆盖贴图");
            assertTrue(resourceExists(modelPath(entry.getValue())),
                    "Create 侧父模型不存在，继承会断链: " + entry.getValue());
        }
    }

    @Test
    void everyTextureSlotUsedByTheParentChainIsOverridden() {
        for (Map.Entry<String, String> entry : EXPECTED_PARENTS.entrySet()) {
            JsonObject model = readJson(OUR_MODELS + entry.getKey() + ".json");
            Set<String> overridden = new TreeSet<>(model.getAsJsonObject("textures").keySet());

            Set<String> used = new TreeSet<>(slotsUsedByParentChain(entry.getValue()));
            used.removeAll(overridden);
            assertTrue(used.isEmpty(),
                    entry.getKey() + " 少了父模型用到的贴图槽位（会回落到 Create 黄铜贴图）: " + used);
        }
    }

    /**
     * 除竖式漏斗刻意保留原样的 {@code #open}（Create 的开口/闭合箭头贴图）之外，任何槽位都
     * 不允许指回 {@code create:} / {@code minecraft:}，且必须能解析到我们自己的 PNG 文件。
     *
     * <p>这条把"槽位指错或多余覆盖了 Create 应保持原样的透明贴图"钉死：一旦有人把
     * {@code base}/{@code block}/{@code direction}/{@code redstone}/{@code back}/{@code frame}
     * 里任意一个写回 Create 的命名空间，漏斗就会漏出金铜色或丢失镂空。</p>
     */
    @Test
    void textureSlotsEitherPointAtOurOwnSpritesOrKeepCreatesThroat() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : EXPECTED_PARENTS.entrySet()) {
            String modelName = entry.getKey();
            JsonObject textures = readJson(OUR_MODELS + modelName + ".json").getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> slot : textures.entrySet()) {
                String id = slot.getValue().getAsString();
                if (isThroatSlot(slot.getKey(), id)) {
                    continue;
                }
                String ours = "dimblend_blocks:block/funnel/";
                if (!id.startsWith(ours)) {
                    problems.add(modelName + " 的槽位 " + slot.getKey() + "=" + id
                            + " 不是自己的贴图（应形如 " + ours + "xxx）");
                    continue;
                }
                String png = "assets/dimblend_blocks/textures/block/funnel/" + id.substring(ours.length()) + ".png";
                if (!resourceExists(png)) {
                    problems.add(modelName + " 的槽位 " + slot.getKey() + " 指向不存在的贴图: " + png);
                }
            }
        }
        assertTrue(problems.isEmpty(), "贴图槽位指向有问题:\n" + String.join("\n", problems));
    }

    /** 竖式漏斗的喉口贴图是 Create 原版的箭头（刻意不重着色），只允许 open/closed 两种。 */
    private static boolean isThroatSlot(String slot, String id) {
        if (!"open".equals(slot)) {
            return false;
        }
        return "create:block/funnel/funnel_open".equals(id) || "create:block/funnel/funnel_closed".equals(id);
    }

    @Test
    void verticalModelsKeepCreatesOpenClosedThroatMapping() {
        // 竖直漏斗的 #open 槽位：未通电=开口、通电=闭合（Create brass_funnel_vertical_* 同款映射）
        assertEquals("create:block/funnel/funnel_open",
                readJson(OUR_MODELS + "dump_funnel_vertical_push_unpowered.json")
                        .getAsJsonObject("textures").get("open").getAsString());
        assertEquals("create:block/funnel/funnel_closed",
                readJson(OUR_MODELS + "dump_funnel_vertical_push_powered.json")
                        .getAsJsonObject("textures").get("open").getAsString());
    }

    @Test
    void itemModelOnlyPointsAtTheBlockItemModel() {
        JsonObject item = readJson("assets/dimblend_blocks/models/item/dump_funnel.json");
        assertEquals("dimblend_blocks:block/funnel/dump_funnel_item", item.get("parent").getAsString());
    }

    // ------------------------------------------------------------ 贴图 alpha

    /**
     * 每一张 {@code dump_funnel*.png} 的 alpha 通道必须与 Create 原版同源贴图<b>逐像素完全一致</b>。
     *
     * <p>重着色只允许改 RGB，不允许动镂空：{@code 0} 与 {@code 255} 之间的任何偏移都会让
     * {@code cutout_mipped} 的 alpha test 在 mipmap 层出现毛边，或者把本该透空的边框/箭头画实。
     * 这里同时盯住三件事——尺寸一致、alpha 值一致、透明像素数一致（非零，证明镂空还在）。</p>
     */
    @Test
    void alphaChannelsMatchCreateOriginalsPixelForPixel() {
        for (Map.Entry<String, String> entry : TEXTURE_SOURCES.entrySet()) {
            String oursPath = OUR_TEXTURES + entry.getKey();
            BufferedImage ours = readImage(oursPath);
            BufferedImage create = readImage(entry.getValue());

            assertEquals(create.getWidth(), ours.getWidth(), oursPath + " 宽度与 Create 原图不一致");
            assertEquals(create.getHeight(), ours.getHeight(), oursPath + " 高度与 Create 原图不一致");

            int transparent = 0;
            List<String> mismatches = new ArrayList<>();
            for (int y = 0; y < ours.getHeight(); y++) {
                for (int x = 0; x < ours.getWidth(); x++) {
                    int ourAlpha = alpha(ours, x, y);
                    int createAlpha = alpha(create, x, y);
                    if (ourAlpha == 0) {
                        transparent++;
                    }
                    if (ourAlpha != createAlpha) {
                        if (mismatches.size() < 8) {
                            mismatches.add("(" + x + "," + y + ") 我们=" + ourAlpha + " 原版=" + createAlpha);
                        }
                    }
                }
            }
            assertTrue(mismatches.isEmpty(),
                    oursPath + " 的 alpha 掩码与 " + entry.getValue() + " 不一致（前 8 处）: "
                            + String.join(" ", mismatches));

            if (NO_CUTOUT_TEXTURES.contains(entry.getKey())) {
                // brass_block 原图本身就是一张不透明板，跟着保留 0 透明像素才是对的
                assertEquals(0, transparent, oursPath + " 应是一张完全不透明的板（对应 create:block/brass_block）");
            } else {
                assertTrue(transparent > 0,
                        oursPath + " 一个透明像素都没有——镂空被填实了，实机会变成实心面");
            }
        }
    }

    /**
     * 所有 {@code alpha == 0} 的像素 RGB 必须归零。
     *
     * <p>这批贴图是从 Create 金铜色重着色成蓝色的，如果透明像素底下残留了原来的非零 RGB，
     * mipmap 采样会把那些颜色扩散到相邻的可见像素边缘（实测就是亮蓝色描边）；更糟的是
     * {@code solid} 层根本不看 alpha，留什么 RGB 就直接画什么颜色，于是整块镂空区域都变成实心色块。
     * 归零之后即使渲染层将来又被写错，最坏也只是黑色间隔而不是蓝色实心面。</p>
     */
    @Test
    void fullyTransparentPixelsCarryNoColour() {
        for (String name : TEXTURE_SOURCES.keySet()) {
            BufferedImage image = readImage(OUR_TEXTURES + name);
            List<String> bleed = new ArrayList<>();
            int transparent = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) != 0) {
                        continue;
                    }
                    transparent++;
                    int rgb = argb & 0xFFFFFF;
                    if (rgb != 0) {
                        if (bleed.size() < 8) {
                            bleed.add(String.format("(%d,%d)=#%06X", x, y, rgb));
                        }
                    }
                }
            }
            assertTrue(bleed.isEmpty(),
                    OUR_TEXTURES + name + " 有 " + transparent + " 个透明像素残留颜色，mipmap 会透出: "
                            + String.join(" ", bleed));
        }
    }

    // ------------------------------------------------------------ 工具

    private static void assertVariantsMatch(String ourBlockstate, String ourModelPrefix, String createBlockstate,
            String createModelPrefix, int expectedVariants) {
        Map<String, String> ours = variantSignatures(ourBlockstate, ourModelPrefix, createModelPrefix);
        Map<String, String> create = variantSignatures(createBlockstate, createModelPrefix, createModelPrefix);

        assertEquals(expectedVariants, ours.size(), ourBlockstate + " 变体数");
        assertEquals(expectedVariants, create.size(), createBlockstate + " 变体数");

        Set<String> differences = new TreeSet<>();
        for (String key : union(ours.keySet(), create.keySet())) {
            String ourValue = ours.get(key);
            String createValue = create.get(key);
            if (ourValue == null) {
                differences.add("缺变体 " + key);
            } else if (createValue == null) {
                differences.add("多出变体 " + key);
            } else if (!ourValue.equals(createValue)) {
                differences.add(key + " : 我们=" + ourValue + " 原版=" + createValue);
            }
        }
        assertTrue(differences.isEmpty(), ourBlockstate + " 与 Create 变体不一致:\n" + String.join("\n", differences));
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> all = new TreeSet<>(left);
        all.addAll(right);
        return all;
    }

    /** 变体 -> "模型|x|y|uvlock"，模型名统一改写成 Create 的命名以便直接比对。 */
    private static Map<String, String> variantSignatures(String path, String ourModelPrefix, String createModelPrefix) {
        JsonObject variants = readJson(path).getAsJsonObject("variants");
        Map<String, String> signatures = new TreeMap<>();
        for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
            JsonElement value = variant.getValue();
            if (!value.isJsonObject()) {
                fail(path + " 的变体 " + variant.getKey() + " 不是单一模型（不支持 weighted 变体）");
            }
            JsonObject object = value.getAsJsonObject();
            String model = object.get("model").getAsString().replace(ourModelPrefix, createModelPrefix);
            signatures.put(variant.getKey(), model
                    + "|x=" + intOrZero(object, "x")
                    + "|y=" + intOrZero(object, "y")
                    + "|uvlock=" + (object.has("uvlock") && object.get("uvlock").getAsBoolean()));
        }
        return signatures;
    }

    private static int intOrZero(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    /** 沿父链递归收集元素面引用的 {@code #槽位} 名（父模型都直接带 elements）。 */
    private static Set<String> slotsUsedByParentChain(String modelRef) {
        Set<String> slots = new TreeSet<>();
        String current = modelRef;
        while (current != null) {
            JsonObject model = readOptionalJson(modelPath(current));
            if (model == null) {
                break;
            }
            JsonElement elements = model.get("elements");
            if (elements != null) {
                for (JsonElement element : elements.getAsJsonArray()) {
                    JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                    for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                        String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                        if (texture.startsWith("#")) {
                            slots.add(texture.substring(1));
                        }
                    }
                }
                break;
            }
            JsonElement parent = model.get("parent");
            current = parent == null ? null : parent.getAsString();
        }
        return slots;
    }

    private static String modelPath(String ref) {
        String namespaced = ref.indexOf(':') >= 0 ? ref : "minecraft:" + ref;
        String namespace = namespaced.substring(0, namespaced.indexOf(':'));
        String path = namespaced.substring(namespaced.indexOf(':') + 1);
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    private static BufferedImage readImage(String classpathPath) {
        try (InputStream in = DumpFunnelAssetsTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            assertNotNull(in, "类路径贴图缺失: " + classpathPath);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "无法解析 PNG: " + classpathPath);
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException("读取贴图失败: " + classpathPath, e);
        }
    }

    private static JsonObject readJson(String classpathPath) {
        JsonObject json = readOptionalJson(classpathPath);
        if (json == null) {
            throw new AssertionError("类路径资源缺失: " + classpathPath);
        }
        return json;
    }

    private static JsonObject readOptionalJson(String classpathPath) {
        try (InputStream in = DumpFunnelAssetsTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean resourceExists(String classpathPath) {
        try (InputStream in = DumpFunnelAssetsTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            return in != null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

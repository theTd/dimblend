package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * T-12：路基从「布尔 + 硬编码圆石」泛化成「材质 + 宽度」后，每个 lane 的取值必须能在
 * 不加载 level、不碰区块的前提下断言——只读 {@link CorridorTrackProfile} 的纯参数。
 *
 * <p>约定：{@code foundationWidth() == 0} 表示该 lane 一条地基都不铺，此时
 * {@code foundationHalfWidth() == -1}。宽度是奇数格数（1、3、7），半宽由它派生，
 * 不再另存一个布尔，避免两处判据打架。
 *
 * <p>材质以方块 id 断言：档案里存 {@link ResourceLocation}（运行时再解析成 {@code Block}），
 * 因为 {@code Blocks.XXX} 常量会触发原版方块类初始化，普通单测里没有 bootstrap 注册表会直接炸。
 *
 * <p>2026-11-02 口径：星光 lane 铺 1 格宽 {@code eternal_starlight:polished_voidstone}，
 * 深暗 lane 铺 1 格宽 {@code minecraft:polished_deepslate}（z 只有 0），下界 3 格黑石，
 * 地表 / 地下 / 暮色仍是 7 格圆石；末地 / 天域 / 深渊 / mod 兜底不铺地基。
 */
class TrackRoadbedTest {
    private static final ResourceLocation COBBLESTONE = ResourceLocation.withDefaultNamespace("cobblestone");
    private static final ResourceLocation BLACKSTONE = ResourceLocation.withDefaultNamespace("blackstone");
    private static final ResourceLocation POLISHED_DEEPSLATE =
            ResourceLocation.withDefaultNamespace("polished_deepslate");
    private static final ResourceLocation POLISHED_VOIDSTONE =
            ResourceLocation.fromNamespaceAndPath("eternal_starlight", "polished_voidstone");

    /** 7 格宽圆石路基的三条 lane：地表桦木 / 地下深色橡木 / 暮色安山岩。 */
    private static final Set<CorridorTrackProfile> SEVEN_WIDE_LANES =
            EnumSet.of(CorridorTrackProfile.SURFACE,
                    CorridorTrackProfile.UNDERGROUND,
                    CorridorTrackProfile.TWILIGHT);

    /** 1 格宽地基的两条 lane：星光虚空石 / 深暗磨制深板岩。 */
    private static final Set<CorridorTrackProfile> SINGLE_BLOCK_LANES =
            EnumSet.of(CorridorTrackProfile.STARLIGHT,
                    CorridorTrackProfile.OTHERSIDE);

    /** 没有地基的 lane，包括未知 mod lane 的兜底档案。 */
    private static final Set<CorridorTrackProfile> FOUNDATIONLESS_LANES =
            EnumSet.of(CorridorTrackProfile.END,
                    CorridorTrackProfile.AETHER,
                    CorridorTrackProfile.VOIDSCAPE,
                    CorridorTrackProfile.MOD);

    @Test
    void theNetherLaneGetsABlackstoneFoundation() {
        assertEquals(BLACKSTONE, CorridorTrackProfile.NETHER.foundationBlockId(),
                "下界 lane 的地基材质必须是 minecraft:blackstone");
        assertEquals(3, CorridorTrackProfile.NETHER.foundationWidth(), "下界 lane 的地基是 3 格宽");
    }

    @Test
    void theNetherFoundationIsExactlyThreeBlocksWide() {
        int halfWidth = CorridorTrackProfile.NETHER.foundationHalfWidth();
        int zMin = OakTrackCorridor.CORRIDOR_Z - halfWidth;
        int zMax = OakTrackCorridor.CORRIDOR_Z + halfWidth;

        assertEquals(1, halfWidth, "3 格宽 = 半宽 1");
        assertEquals(-1, zMin, "3 格宽 = z 从 −1 起（z 是走廊横向，不是高度）");
        assertEquals(1, zMax, "3 格宽 = z 到 +1 止");
        assertEquals(3, zMax - zMin + 1, "地基正好 3 格宽");
    }

    @Test
    void starlightGetsASingleVoidstoneBlock() {
        CorridorTrackProfile profile = CorridorTrackProfile.STARLIGHT;
        assertEquals(POLISHED_VOIDSTONE, profile.foundationBlockId(),
                "星光 lane 的地基材质必须是 eternal_starlight:polished_voidstone");
        assertEquals(1, profile.foundationWidth(), "星光 lane 的地基是 1 格宽");
        assertEquals(0, profile.foundationHalfWidth(), "1 格宽 = 半宽 0，只写 z = CORRIDOR_Z");
        assertTrue(profile.hasFoundation(), "1 格宽也是地基，hasFoundation() 必须为真");
    }

    @Test
    void othersideGetsASinglePolishedDeepslateBlock() {
        CorridorTrackProfile profile = CorridorTrackProfile.OTHERSIDE;
        assertEquals(POLISHED_DEEPSLATE, profile.foundationBlockId(),
                "深暗 lane 的地基材质必须是 minecraft:polished_deepslate");
        assertEquals(1, profile.foundationWidth(), "深暗 lane 的地基是 1 格宽");
        assertEquals(0, profile.foundationHalfWidth(), "1 格宽 = 半宽 0，只写 z = CORRIDOR_Z");
        assertTrue(profile.hasFoundation(), "1 格宽也是地基，hasFoundation() 必须为真");
    }

    @Test
    void theLanesThatHadARoadbedKeepCobblestoneAndSevenBlocks() {
        for (CorridorTrackProfile profile : SEVEN_WIDE_LANES) {
            assertEquals(COBBLESTONE, profile.foundationBlockId(),
                    "有路基的 lane 材质维持圆石：" + profile);
            assertEquals(7, profile.foundationWidth(), "有路基的 lane 宽度维持 7 格：" + profile);
            assertEquals(3, profile.foundationHalfWidth(), "7 格宽 = 半宽 3：" + profile);
            assertTrue(profile.hasFoundation(), "7 格宽必须有地基：" + profile);
        }
    }

    @Test
    void theLanesWithoutARoadbedStillWriteNoFoundation() {
        for (CorridorTrackProfile profile : FOUNDATIONLESS_LANES) {
            assertEquals(0, profile.foundationWidth(),
                    "无路基的 lane 宽度必须是 0，表示一格都不铺：" + profile);
            assertEquals(-1, profile.foundationHalfWidth(),
                    "宽度 0 派生出半宽 −1（writer 靠它早退）：" + profile);
            assertFalse(profile.hasFoundation(), "宽度 0 不能报出地基：" + profile);
        }
    }

    @Test
    void everyLaneIsExactlyOneOfTheThreeWidthClasses() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            int width = profile.foundationWidth();
            boolean known = SEVEN_WIDE_LANES.contains(profile)
                    || SINGLE_BLOCK_LANES.contains(profile)
                    || FOUNDATIONLESS_LANES.contains(profile)
                    || profile == CorridorTrackProfile.NETHER;
            assertTrue(known, "每条 lane 都要在测试里被明确归类（新增 lane 请补断言）：" + profile);
            assertTrue(width == 0 || width == 1 || width == 3 || width == 7,
                    "宽度只能是 0 / 1 / 3 / 7：" + profile + " = " + width);
        }
    }

    @Test
    void everyProfileCarriesAMaterialIdSoTheWriterNeverSeesNull() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            assertNotNull(profile.foundationBlockId(), "材质 id 不能为 null（无地基由宽度 0 表达）：" + profile);
        }
    }

    @Test
    void widthAndHalfWidthNeverDisagree() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            assertEquals(profile.foundationWidth() > 0, profile.hasFoundation(),
                    "hasFoundation() 必须紧跟宽度，不能单独存一份：" + profile);
            assertEquals(profile.hasFoundation(), profile.foundationHalfWidth() >= 0,
                    "hasFoundation() 必须等价于半宽 ≥ 0：" + profile);
            if (profile.hasFoundation()) {
                assertEquals(profile.foundationWidth(),
                        2 * profile.foundationHalfWidth() + 1,
                        "奇数宽度必须能由半宽还原：" + profile);
            }
        }
    }
}

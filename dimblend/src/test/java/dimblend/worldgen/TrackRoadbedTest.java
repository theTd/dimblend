package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * T-12：路基从「布尔 + 硬编码圆石」泛化成「材质 + 半宽」后，每个 lane 的取值必须能在
 * 不加载 level、不碰区块的前提下断言——只读 {@link CorridorTrackProfile} 的纯参数。
 *
 * <p>约定：{@code foundationHalfWidth() == 0} 表示该 lane 一条地基都不铺。半宽 0 就是
 * 「无地基」的唯一表达，不再另存一个布尔，避免两处判据打架。
 *
 * <p>材质以方块 id 断言：档案里存 {@link ResourceLocation}（运行时再解析成 {@code Block}），
 * 因为 {@code Blocks.XXX} 常量会触发原版方块类初始化，普通单测里没有 bootstrap 注册表会直接炸。
 *
 * <p>下界 lane：{@code minecraft:blackstone}（不是磨制黑石、不是镶金黑石），半宽 1 = 3 格宽
 * （z −1..+1）。其余 lane 维持现状：材质仍是圆石，有路基的三条半宽仍是 3（7 格），
 * 原本无路基的五条 + mod 兜底半宽为 0。
 */
class TrackRoadbedTest {
    private static final ResourceLocation COBBLESTONE = ResourceLocation.withDefaultNamespace("cobblestone");
    private static final ResourceLocation BLACKSTONE = ResourceLocation.withDefaultNamespace("blackstone");

    /** 当前有路基的三条 lane：地表桦木 / 地下深色橡木 / 暮色安山岩（现状 7 格 = 半宽 3）。 */
    private static final Set<CorridorTrackProfile> ROADBED_LANES =
            EnumSet.of(CorridorTrackProfile.SURFACE,
                    CorridorTrackProfile.UNDERGROUND,
                    CorridorTrackProfile.TWILIGHT);

    /** 当前没有路基的 lane，包括未知 mod lane 的兜底档案。 */
    private static final Set<CorridorTrackProfile> FOUNDATIONLESS_LANES =
            EnumSet.of(CorridorTrackProfile.END,
                    CorridorTrackProfile.STARLIGHT,
                    CorridorTrackProfile.OTHERSIDE,
                    CorridorTrackProfile.AETHER,
                    CorridorTrackProfile.VOIDSCAPE,
                    CorridorTrackProfile.MOD);

    @Test
    void theNetherLaneGetsABlackstoneFoundation() {
        assertEquals(BLACKSTONE, CorridorTrackProfile.NETHER.foundationBlockId(),
                "下界 lane 的地基材质必须是 minecraft:blackstone");
        assertEquals(1, CorridorTrackProfile.NETHER.foundationHalfWidth(),
                "下界 lane 的地基半宽是 1");
    }

    @Test
    void theNetherFoundationIsExactlyThreeBlocksWide() {
        int halfWidth = CorridorTrackProfile.NETHER.foundationHalfWidth();
        int zMin = OakTrackCorridor.CORRIDOR_Z - halfWidth;
        int zMax = OakTrackCorridor.CORRIDOR_Z + halfWidth;

        assertEquals(-1, zMin, "3 格宽 = z 从 −1 起（z 是走廊横向，不是高度）");
        assertEquals(1, zMax, "3 格宽 = z 到 +1 止");
        assertEquals(3, zMax - zMin + 1, "地基正好 3 格宽");
    }

    @Test
    void theNetherLaneIsTheOnlyBlackstoneLane() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            if (profile == CorridorTrackProfile.NETHER) {
                continue;
            }
            assertEquals(COBBLESTONE, profile.foundationBlockId(),
                    "只有下界 lane 换黑石，这条仍是圆石：" + profile);
        }
    }

    @Test
    void theLanesThatHadARoadbedKeepCobblestoneAndSevenBlocks() {
        for (CorridorTrackProfile profile : ROADBED_LANES) {
            assertEquals(COBBLESTONE, profile.foundationBlockId(),
                    "有路基的 lane 材质维持圆石：" + profile);
            assertEquals(3, profile.foundationHalfWidth(),
                    "有路基的 lane 半宽维持 3（7 格宽）：" + profile);
        }
    }

    @Test
    void theLanesWithoutARoadbedStillWriteNoFoundation() {
        for (CorridorTrackProfile profile : FOUNDATIONLESS_LANES) {
            assertEquals(0, profile.foundationHalfWidth(),
                    "原本无路基的 lane 半宽必须是 0，表示一格都不铺：" + profile);
        }
    }

    @Test
    void noLaneOtherThanTheNetherOrTheRoadbedThreeHasAWidth() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            if (profile == CorridorTrackProfile.NETHER || ROADBED_LANES.contains(profile)) {
                continue;
            }
            assertEquals(0, profile.foundationHalfWidth(), "不该有地基宽度的 lane：" + profile);
        }
    }

    @Test
    void everyProfileCarriesAMaterialIdSoTheWriterNeverSeesNull() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            assertNotNull(profile.foundationBlockId(), "材质 id 不能为 null（无地基由半宽 0 表达）：" + profile);
        }
    }

    @Test
    void onlyTheLanesWithAWidthReportAFoundation() {
        for (CorridorTrackProfile profile : CorridorTrackProfile.values()) {
            assertEquals(profile.foundationHalfWidth() > 0, profile.hasFoundation(),
                    "hasFoundation() 必须紧跟半宽，不能单独存一份：" + profile);
        }
    }
}
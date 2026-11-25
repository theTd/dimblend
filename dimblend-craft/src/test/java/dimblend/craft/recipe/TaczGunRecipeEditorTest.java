package dimblend.craft.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

/**
 * TACZ 枪械工作台配方追加精密构件（决议 21）的判定与规划。
 *
 * <p>真正的改写器 {@link TaczGunRecipeEditor} 全部走反射（编译期不依赖 tacz），
 * 它的构造器/字段名只能实机验证；这里覆盖的是它的两条命题：
 * <b>怎么认出「枪」配方</b>、<b>已有精密构件时合并而不是重复追加</b>。
 * 这部分逻辑放在 {@link GunSmithTableMechanism}，正是为了让它在离线单测里跑得起来。</p>
 *
 * <p>判定顺序（{@code report-w5 §5f}）：{@code RawGunTableResult.type} 优先；
 * 下游构造/初始化的差异使 {@code type} 读不到时（重载阶段 read 不到、或 init 之后
 * {@code raw} 已被置 null），退回产物物品 ID —— 且该兜底只在 {@code type} 缺失时生效，
 * 以免「弹药配方产出 tacz:ammo」之类的情况被误判。</p>
 */
class TaczGunRecipeEditorTest {

    private static final ResourceLocation GUN_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");
    private static final ResourceLocation AMMO_ITEM = ResourceLocation.parse("tacz:ammo");
    private static final ResourceLocation ATTACHMENT_ITEM = ResourceLocation.parse("tacz:attachment");

    /** 上游写明 {@code type == "gun"} 就是枪，产物物品是什么都不影响判定。 */
    @Test
    void gunResultTypeIsAuthoritative() {
        assertTrue(GunSmithTableMechanism.isGunCraft("gun", null),
                "type = gun 且产物尚未初始化时必须判为枪");
        assertTrue(GunSmithTableMechanism.isGunCraft("gun", GUN_ITEM),
                "type = gun 且产物已初始化时必须判为枪");
        assertTrue(GunSmithTableMechanism.isGunCraft("gun", AMMO_ITEM),
                "type = gun 时产物 ID 不参与判定（以上游类型为准）");
    }

    /** 同一配方类型下的弹药 / 配件 / custom 产物一条都不能算枪。 */
    @Test
    void nonGunResultTypesAreRejected() {
        for (String type : List.of("ammo", "attachment", "custom")) {
            assertFalse(GunSmithTableMechanism.isGunCraft(type, GUN_ITEM),
                    "type = " + type + " 时即使产物物品 ID 像枪也不得判为枪");
            assertFalse(GunSmithTableMechanism.isGunCraft(type, null),
                    "type = " + type + " 时产物为空也不得判为枪");
        }
    }

    /** type 缺失时才看产物物品：这正是 init 之后 {@code raw} 被清空的路径。 */
    @Test
    void missingTypeFallsBackToOutputItem() {
        assertTrue(GunSmithTableMechanism.isGunCraft(null, GUN_ITEM),
                "读不到 type 时靠 tacz:modern_kinetic_gun 认枪");
        assertFalse(GunSmithTableMechanism.isGunCraft(null, AMMO_ITEM), "tacz:ammo 不是枪");
        assertFalse(GunSmithTableMechanism.isGunCraft(null, ATTACHMENT_ITEM), "tacz:attachment 不是枪");
        assertFalse(GunSmithTableMechanism.isGunCraft(null, null),
                "既没有 type 也没有产物（重载阶段尚未初始化）时无法判定，必须保守跳过");
    }

    /** 已经有一条「只吃精密构件」的材料时，改它的数量而不是再加一条。 */
    @Test
    void existingPrecisionMechanismEntryIsMergedNotDuplicated() {
        List<GunSmithTableMechanism.Input> inputs = new ArrayList<>();
        inputs.add(new GunSmithTableMechanism.Input(false, 16));
        inputs.add(new GunSmithTableMechanism.Input(false, 80));
        inputs.add(new GunSmithTableMechanism.Input(true, 1));

        assertEquals(OptionalInt.of(2), GunSmithTableMechanism.mergingIndex(inputs),
                "应命中第 3 条（下标 2）的精密构件材料");
        // 合并语义：数量 +1（由 TaczGunRecipeEditor 落实），不是再追加一条。
        assertEquals(1, inputs.get(2).count(), "规划阶段只读不改数量，上游数量应原样保留");
    }

    /** 一条都没有时返回 empty：调用方追加新条目。 */
    @Test
    void missingPrecisionMechanismEntryMeansAppend() {
        assertTrue(GunSmithTableMechanism.mergingIndex(List.of()).isEmpty(), "空材料表必须走追加");
        assertTrue(GunSmithTableMechanism.mergingIndex(List.of(
                new GunSmithTableMechanism.Input(false, 16),
                new GunSmithTableMechanism.Input(false, 4))).isEmpty(), "全是普通材料时必须走追加");
        // 「接受精密构件但不止它一种」（标签 / 多物品 Ingredient）不算只吃这一种：
        // 判定放行会改变该材料原本的接受范围，所以只能追加一条新的。
        assertTrue(GunSmithTableMechanism.mergingIndex(List.of(
                new GunSmithTableMechanism.Input(false, 1))).isEmpty(),
                "多物品材料不得被当作精密构件位");
    }

    /** 多条都只吃精密构件时命中最靠前的一条，避免多算。 */
    @Test
    void firstMatchingEntryWins() {
        assertEquals(OptionalInt.of(0), GunSmithTableMechanism.mergingIndex(List.of(
                new GunSmithTableMechanism.Input(true, 2),
                new GunSmithTableMechanism.Input(true, 5))),
                "重复的精密构件材料只应加在第一条上，否则数量会被算两次");
    }

    /** 常量必须与需求 / 上游注册名一致（写错就整条规则空转，日志里只剩一句未命中）。 */
    @Test
    void constantsMatchUpstreamRegistryNames() {
        assertEquals("tacz:gun_smith_table_crafting", GunSmithTableMechanism.RECIPE_TYPE.toString());
        assertEquals("create:precision_mechanism", GunSmithTableMechanism.PRECISION_MECHANISM.toString());
        assertEquals("tacz:modern_kinetic_gun", GunSmithTableMechanism.GUN_OUTPUT_ITEM.toString());
        assertEquals("gun", GunSmithTableMechanism.GUN_RESULT_TYPE);
        assertEquals("枪械工作台配方追加精密构件", GunSmithTableMechanism.LABEL);
    }
}

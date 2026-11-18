package dimblend.experience.compat.dirtywater;

/**
 * 工作盆脏水策略的纯判定核心（无 Create、无注册表依赖，单测直接覆盖）。
 *
 * <p>工作盆既能做面团/茶，也能做药水与混凝土/染料一类工业配方，单看"进了水"无法判断该不该拒。
 * 判定用玩家的显式意图（过滤槽）+ 盆里已有物品的类型：</p>
 * <ul>
 *   <li>过滤槽非空：药水/非食物过滤槽放行，食物过滤槽拒收；</li>
 *   <li>过滤槽空：盆里空 → 拒收（{@code purity0_basin_default_block}）；</li>
 *   <li>过滤槽空且有药水 → 药水酿造，放行；</li>
 *   <li>过滤槽空且只有非食物工业料 → 放行；混有食物/面团/茶/糖等 → 拒收。</li>
 * </ul>
 */
public final class BasinDirtyWaterIntent {

    /** 物品在"食物/药水/其他"三分法里的归属。 */
    public enum ItemKind {
        POTION,
        FOOD,
        OTHER
    }

    /** 工作盆当前意图。 */
    public enum Intent {
        /** 过滤槽空、盆里也空：脏水默认不许进。 */
        EMPTY,
        /** 药水酿造：放行。 */
        POTION_BREWING,
        /** 非食物工业配方：放行。 */
        INDUSTRIAL,
        /** 食物/饮品/面团：拒收。 */
        FOOD_PREP
    }

    /** 脏水是否被允许进入该意图的工作盆。 */
    public static boolean allowsDirtyWater(Intent intent) {
        return intent == Intent.POTION_BREWING || intent == Intent.INDUSTRIAL;
    }

    /**
     * 意图解析（纯函数）。
     *
     * @param filterEmpty  过滤槽是否为空
     * @param filterKind   过滤槽物品分类（空过滤槽时忽略）
     * @param basinEmpty   输入库存是否为空
     * @param anyPotion    输入库存里是否有药水
     * @param anyFoodLike  输入库存里是否有食物/饮品/面团/茶/糖一类
     */
    public static Intent resolve(boolean filterEmpty, ItemKind filterKind, boolean basinEmpty,
            boolean anyPotion, boolean anyFoodLike) {
        if (!filterEmpty) {
            return switch (filterKind) {
                case POTION -> Intent.POTION_BREWING;
                case FOOD -> Intent.FOOD_PREP;
                case OTHER -> Intent.INDUSTRIAL;
            };
        }
        if (basinEmpty) {
            return Intent.EMPTY;
        }
        if (anyPotion) {
            return Intent.POTION_BREWING;
        }
        return anyFoodLike ? Intent.FOOD_PREP : Intent.INDUSTRIAL;
    }

    private BasinDirtyWaterIntent() {
    }
}

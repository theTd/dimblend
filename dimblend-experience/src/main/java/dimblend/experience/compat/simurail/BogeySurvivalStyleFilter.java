package dimblend.experience.compat.simurail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.resources.ResourceLocation;

/**
 * 生存模式物理转向架样式菜单的收缩口径（2026-11-26 slice-1 #1B）。
 *
 * <p>本类<b>刻意不引用任何 Simurail / Create 类型</b>（Simurail 是 compileOnly 依赖，
 * 不进单测 runtime classpath）：只保留可纯函数化的名单顺序与菜单行数计算；
 * 把名单翻成 {@code BogeyEntry} / {@code BogeyEntryCategory} / {@code BogeyMenuSelection}
 * 的胶水写在 {@code mixin/compat/simurail/BogeyMenuScreenSurvivalMixin} 里。</p>
 *
 * <p>口径（运行 jar {@code simurail-1.21.1-0.0.0-a+ecd2dd3.jar} 字节码核对）：</p>
 * <ul>
 *   <li>四个允许样式 = {@code RailwaysBogeys} 里 {@code railways:invisible} /
 *       {@code railways:wide/standard} / {@code railways:wide/scotch_yoke/large} /
 *       {@code railways:wide/scotch_yoke/comically_large}（中文依次为
 *       "隐形" / "小型宽轨标准" / "大型宽轨苏格兰轭" / "巨型宽轨苏格兰轭"，翻译键
 *       {@code simurail_bogey_entry.railways.*}）；</li>
 *   <li>这四项在出厂分类树里同属<b>一个</b> {@code BogeyEntryCategory}
 *       （{@code RailwaysBogeys.SPECIAL}，"汽鸣铁道 / 特殊"），其余六类
 *       （单轴 / 二轴 / …）与另外两个主分类（{@code create}、{@code create_bb}）必须整体不可见。</li>
 * </ul>
 *
 * <p>菜单行数口径来自 {@code PhysicsBogeyMenuScreen#updateMenuItemWidgets} 字节码：
 * {@code subPathDepth = clamp(path.size() - 1, 0, 3)}、{@code entriesPerPage = 6 - subPathDepth}、
 * {@code pageCount = max(1, ceilDiv(entries.size(), entriesPerPage))}，
 * 条目行最多 6 个（{@code entryLabels[6]}），超出的走翻页。生存模式只剩
 * "一个条目级分类"（{@code path.size() == 1}），所以 depth 恒为 0、每页 6 行、4 项一页装完，
 * 翻页控件永远只有第 1 页——这几条是它不会越界 {@code IndexOutOfBoundsException} 的依据。</p>
 */
public final class BogeySurvivalStyleFilter {

    /** 菜单固定行数：{@code PhysicsBogeyMenuScreen} 里 {@code entriesPerPage = 6 - subPathDepth}。 */
    public static final int MENU_ENTRY_ROWS = 6;

    /** 子分类层数上限：{@code Math.clamp(path.size() - 1, 0, 3)}。 */
    public static final int MAX_SUB_PATH_DEPTH = 3;

    /** 只有拿到了玩家对象且不是创造模式才收缩（与服务端合并闸同口径）。 */
    public static boolean shouldRestrict(boolean hasPlayer, boolean creative) {
        return hasPlayer && !creative;
    }

    public static boolean isAllowed(ResourceLocation entryId) {
        return SimurailSurvivalBogeyRules.isAllowedEntry(entryId);
    }

    /**
     * 把"当前确实可选"的样式 id 收成白名单顺序的一串（隐形 → 小型宽轨标准 →
     * 大型宽轨苏格兰轭 → 巨型宽轨苏格兰轭）。
     *
     * <p>{@code available} 是候选（顺序无所谓，含重复、含白名单外的 id 都行），
     * {@code selectable} 表达"这一项在当前载具/环境下能不能选"（注册表里存在、
     * 以及倒置载具只允许可倒置样式）。</p>
     */
    public static List<ResourceLocation> selectableIds(List<ResourceLocation> available,
            Predicate<ResourceLocation> selectable) {
        List<ResourceLocation> ordered = new ArrayList<>(SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS.size());
        LinkedHashSet<ResourceLocation> candidates = new LinkedHashSet<>();
        for (ResourceLocation id : available) {
            if (id != null) {
                candidates.add(id);
            }
        }
        for (ResourceLocation id : SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS) {
            if (candidates.contains(id) && selectable.test(id)) {
                ordered.add(id);
            }
        }
        return List.copyOf(ordered);
    }

    /** 当前子分类层数下每页能放几行条目。 */
    public static int rowsPerPage(int subPathDepth) {
        int depth = Math.max(0, Math.min(MAX_SUB_PATH_DEPTH, subPathDepth));
        return MENU_ENTRY_ROWS - depth;
    }

    public static int pageCount(int entryCount, int rowsPerPage) {
        if (rowsPerPage <= 0) {
            return 1;
        }
        return Math.max(1, (entryCount + rowsPerPage - 1) / rowsPerPage);
    }

    /**
     * 第 {@code page} 页第 {@code row} 行对应的条目下标；越界（该行留空）返回 {@code -1}。
     *
     * <p>{@code updateEntryWidgets} 本身就是"取到下标就 break"，这里把那条边界显式化，
     * 便于单测钉死"隐藏掉条目后不会拿到越界下标"。</p>
     */
    public static int entryIndex(int page, int row, int rowsPerPage, int entryCount) {
        if (page < 0 || row < 0 || rowsPerPage <= 0) {
            return -1;
        }
        int index = page * rowsPerPage + row;
        return index < entryCount ? index : -1;
    }

    private BogeySurvivalStyleFilter() {
    }
}

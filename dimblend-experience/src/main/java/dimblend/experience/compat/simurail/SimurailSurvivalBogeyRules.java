package dimblend.experience.compat.simurail;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

/**
 * 生存模式转向架菜单收缩口径（2026-11-25 LOCKED #3 {@code bogey_survival_menu}）。
 *
 * <p>本类<b>刻意不引用任何 Simurail 类型</b>：Simurail 是 {@code compileOnly} 依赖，
 * 不进单测 classpath。这里只保留可纯函数化的名单与判定；把名单翻译成
 * {@code BogeyEntry} / {@code BogeyMenuSelection} 的胶水在
 * {@link SimurailSurvivalBogeyGlue}。</p>
 *
 * <p>生存模式允许的四种：隐形、宽轨标准（小）、宽轨 scotch yoke（大）、宽轨 scotch yoke
 * （特大）。其余样式、物理参数、角度/位移/连接件/控制模式一并在选项界面里藏掉；
 * 创造模式完全不受影响。</p>
 */
public final class SimurailSurvivalBogeyRules {

    public static final String LANG_SURVIVAL_STYLES = "dimblend_experience.bogey.survival_styles";

    /** 生存模式可选的转向架样式（来源：Railways 的 BogeyEntry id）。 */
    public static final ResourceLocation INVISIBLE = ResourceLocation.parse("railways:invisible");
    public static final ResourceLocation WIDE_STANDARD = ResourceLocation.parse("railways:wide/standard");
    public static final ResourceLocation WIDE_SCOTCH_LARGE =
            ResourceLocation.parse("railways:wide/scotch_yoke/large");
    public static final ResourceLocation WIDE_SCOTCH_XXL =
            ResourceLocation.parse("railways:wide/scotch_yoke/comically_large");

    public static final List<ResourceLocation> ALLOWED_ENTRY_IDS =
            List.of(INVISIBLE, WIDE_STANDARD, WIDE_SCOTCH_LARGE, WIDE_SCOTCH_XXL);

    /** 选项界面里应当移除控件并置灰标题的六组设置。 */
    public static final List<String> HIDDEN_OPTION_GROUPS =
            List.of("physics", "rotation", "offset", "vertical", "control", "connector");

    public static boolean isAllowedEntry(ResourceLocation entryId) {
        return entryId != null && ALLOWED_ENTRY_IDS.contains(entryId);
    }

    /** 是否保留玩家发来的样式：不变更永远允许；变更只允许落到白名单样式。 */
    public static boolean keepsType(boolean unchanged, boolean targetAllowed) {
        return unchanged || targetAllowed;
    }

    /** 生存模式只接受 stress 变更，且必须夹在 [0, 32]。 */
    public static float sanitizeStress(float incoming) {
        if (Float.isNaN(incoming)) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(32.0F, incoming));
    }

    private SimurailSurvivalBogeyRules() {
    }
}

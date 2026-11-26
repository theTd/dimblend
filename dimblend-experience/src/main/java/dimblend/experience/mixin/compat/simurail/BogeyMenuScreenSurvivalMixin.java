package dimblend.experience.mixin.compat.simurail;

import java.util.List;
import java.util.Optional;

import com.crystaelix.simurail.api.bogey.menu.BogeyCategory;
import com.crystaelix.simurail.api.bogey.menu.BogeyEntry;
import com.crystaelix.simurail.api.bogey.menu.BogeyEntryCategory;
import com.crystaelix.simurail.api.bogey.menu.BogeyMenuManager;
import com.crystaelix.simurail.api.bogey.menu.BogeyMenuSelection;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyMenuScreen;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dimblend.experience.compat.simurail.BogeySurvivalStyleFilter;
import dimblend.experience.compat.simurail.SimurailSurvivalBogeyRules;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 生存模式的物理转向架"显示样式"菜单只剩一个分类，分类下只有四种样式
 * （2026-11-26 slice-1 #1B，接在 2026-11-25 LOCKED #3 之后再收一次）。
 *
 * <p>目标 {@code PhysicsBogeyMenuScreen} 的结构（{@code javap -p -c} 对
 * {@code simurail-1.21.1-0.0.0-a+ecd2dd3.jar} 实测）：</p>
 * <ul>
 *   <li>构造器偏移 118 {@code BogeyMenuManager.getCategories(Z)} 直接赋值给
 *       {@code final mainCategories}（"分类"下拉的选项表）；</li>
 *   <li>构造器偏移 135 {@code BogeyMenuManager.findEntry(BogeyRenderedType, Z)} 得到初始
 *       {@code path}（分类路径）与 {@code selectedEntry}；偏移 153 在 {@code EMPTY} 时回退
 *       {@code defaultEntry(Z)}；</li>
 *   <li>{@code updateMenuItemWidgets} 里 {@code subPathDepth = clamp(path.size() - 1, 0, 3)}，
 *       子分类下拉（标题 "子类别"）逐层读 {@code path.get(i).children(inverted)}，
 *       条目行读 {@code path.getLast() instanceof BogeyEntryCategory}；</li>
 *   <li>{@code changeMainCategory(int)} 偏移 13 {@code getCategories()}（无参、非 inverted 列表）
 *       之后偏移 17 {@code List.get(I)}——全方法唯一一处。</li>
 * </ul>
 *
 * <p>上一轮只换了 {@code mainCategories}。初始 {@code path} 仍来自真实分类树，
 * 于是 {@code subPathDepth == 1}：子分类下拉照样列出"特殊 / 单轴 / 二轴 …"，
 * 条目区照样列出 {@code SPECIAL} 的全部 8 项，且 {@code mainCategories.indexOf(path.get(0))}
 * 为 -1（合成分类与真实分类不是同一个实例）。这一轮把<b>三处一起换</b>，且换的是
 * <b>同一个实例</b>（{@link #dimblend$survivalCategory} 持有，构造器里两个 handler 共用）：</p>
 * <ol>
 *   <li>"分类"下拉 → 唯一一个条目级分类（内含白名单四项，按白名单顺序）；</li>
 *   <li>初始/回退选择 → 路径压成 {@code [同一个分类]}，{@code path.size() == 1} ⇒ depth 0 ⇒
 *       子分类下拉整排不创建（其余分类因此"不可见"）、4 项一页装完（翻页恒 1 页）；</li>
 *   <li>{@code changeMainCategory} 的取分类 → 复用同一份过滤后列表（否则会取到真实主分类）。</li>
 * </ol>
 *
 * <p>边界：当前已选样式在白名单内 → 预选它（预览与确认都用这一项，{@code optionValues}
 * 沿用 {@code findEntry} 的结果）；不在白名单 → {@code selectedEntry} 留空、不预选任何样式，
 * 玩家必须自己从四项里挑一个，不会因为"默认选中项落在被隐藏样式上"而越界或显示空项。
 * 创造模式整条路径都不进（{@link BogeySurvivalStyleFilter#shouldRestrict}）。</p>
 */
@Mixin(value = PhysicsBogeyMenuScreen.class, remap = false)
public abstract class BogeyMenuScreenSurvivalMixin {

    @Shadow
    @Final
    boolean inverted;

    @Shadow
    @Final
    List<BogeyCategory<?>> mainCategories;

    /** 本屏幕实例是否走了生存收缩，以及那个唯一分类实例（保证 {@code mainCategories} 与 {@code path} 指向同一个）。 */
    @Unique
    private boolean dimblend$survivalMenu;

    @Unique
    private BogeyCategory<?> dimblend$survivalCategory;

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuManager;getCategories(Z)Ljava/util/List;"),
            require = 1,
            allow = 1
    )
    private List<BogeyCategory<?>> dimblend$filterMainCategories(List<BogeyCategory<?>> original) {
        if (!dimblend$restricted()) {
            return original;
        }
        BogeyEntryCategory styles = dimblend$buildSurvivalStyles(this.inverted);
        if (styles == null) {
            return original;
        }
        this.dimblend$survivalCategory = styles;
        this.dimblend$survivalMenu = true;
        return List.of(styles);
    }

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuManager;findEntry"
                            + "(Lcom/crystaelix/simurail/api/bogey/BogeyRenderedType;Z)"
                            + "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuSelection;"),
            require = 1,
            allow = 1
    )
    private BogeyMenuSelection dimblend$restrictSelection(BogeyMenuSelection original) {
        return dimblend$normalizeSelection(original);
    }

    @ModifyExpressionValue(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuManager;defaultEntry(Z)"
                            + "Lcom/crystaelix/simurail/api/bogey/menu/BogeyMenuSelection;"),
            require = 1,
            allow = 1
    )
    private BogeyMenuSelection dimblend$restrictDefaultSelection(BogeyMenuSelection original) {
        return dimblend$normalizeSelection(original);
    }

    @Redirect(
            method = "changeMainCategory",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/List;get(I)Ljava/lang/Object;"),
            require = 1,
            allow = 1
    )
    private Object dimblend$reuseFilteredCategories(List<?> ignored, int index) {
        return this.mainCategories.get(index);
    }

    /** 把真实分类树里的路径/选中项压到那个唯一分类上（同一实例，所以 {@code indexOf} 是 0）。 */
    @Unique
    private BogeyMenuSelection dimblend$normalizeSelection(BogeyMenuSelection original) {
        if (!this.dimblend$survivalMenu || this.dimblend$survivalCategory == null) {
            return original;
        }
        BogeyCategory<?> styles = this.dimblend$survivalCategory;
        Optional<BogeyEntry> entry = original.entry()
                .filter(candidate -> BogeySurvivalStyleFilter.isAllowed(candidate.id()));
        if (entry.isEmpty()) {
            return new BogeyMenuSelection(List.of(styles), Optional.empty(), List.of());
        }
        return new BogeyMenuSelection(List.of(styles), entry, original.optionValues());
    }

    /** 合成分类：白名单四项（倒置载具再剔掉不可倒置项），注册表里一个都不剩时返回 null。 */
    @Unique
    private static BogeyEntryCategory dimblend$buildSurvivalStyles(boolean inverted) {
        List<ResourceLocation> allowed = BogeySurvivalStyleFilter.selectableIds(
                SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS,
                id -> {
                    BogeyEntry entry = BogeyMenuManager.getEntry(id);
                    return entry != null && (!inverted || entry.type().invertible());
                });
        if (allowed.isEmpty()) {
            return null;
        }
        List<BogeyEntry> entries = allowed.stream().map(BogeyMenuManager::getEntry).toList();
        return new BogeyEntryCategory(
                Component.translatable(SimurailSurvivalBogeyRules.LANG_SURVIVAL_STYLES), entries);
    }

    @Unique
    private static boolean dimblend$restricted() {
        Minecraft minecraft = Minecraft.getInstance();
        return BogeySurvivalStyleFilter.shouldRestrict(
                minecraft.player != null, minecraft.player != null && minecraft.player.isCreative());
    }
}

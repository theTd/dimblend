package dimblend.experience.compat.simurail;

import java.util.ArrayList;
import java.util.List;

import com.crystaelix.simurail.api.bogey.BogeyRenderedType;
import com.crystaelix.simurail.api.bogey.menu.BogeyCategory;
import com.crystaelix.simurail.api.bogey.menu.BogeyEntry;
import com.crystaelix.simurail.api.bogey.menu.BogeyEntryCategory;
import com.crystaelix.simurail.api.bogey.menu.BogeyMenuManager;
import com.crystaelix.simurail.api.bogey.menu.BogeyMenuSelection;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyOptions;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 把 {@link SimurailSurvivalBogeyRules} 的名单翻译成 Simurail 菜单/选项对象。
 *
 * <p>所有 Simurail 类型只出现在这里：规则本体保持纯函数（可单测），
 * 本类只在 mixin 的生存模式分支里被调用，创造模式不会触达。</p>
 */
public final class SimurailSurvivalBogeyGlue {

    /** 生存模式下的主分类列表：压缩成一个"生存可用样式"分类。 */
    public static List<BogeyCategory<?>> filterMainCategories(List<BogeyCategory<?>> original,
            boolean inverted) {
        List<BogeyEntry> allowed = allowedEntries(inverted);
        if (allowed.isEmpty()) {
            return original;
        }
        return List.of(new BogeyEntryCategory(
                Component.translatable(SimurailSurvivalBogeyRules.LANG_SURVIVAL_STYLES), allowed));
    }

    /** 当前样式不在白名单时用的默认选中状态：只落到白名单分类，不预选任何样式。 */
    public static BogeyMenuSelection allowedSelection(boolean inverted) {
        List<BogeyEntry> allowed = allowedEntries(inverted);
        BogeyCategory<?> category = new BogeyEntryCategory(
                Component.translatable(SimurailSurvivalBogeyRules.LANG_SURVIVAL_STYLES), allowed);
        return new BogeyMenuSelection(List.of(category), Optional.empty(), List.of());
    }

    /** 服务端合并：只接受 stress 变更与白名单内的样式变更，其余沿用既有值。 */
    public static PhysicsBogeyOptions mergeForSurvival(PhysicsBogeyOptions existing,
            PhysicsBogeyOptions incoming) {
        PhysicsBogeyOptions merged = new PhysicsBogeyOptions().set(existing);
        if (incoming != null) {
            merged.setStress(SimurailSurvivalBogeyRules.sanitizeStress(incoming.stress));
            if (isAllowedType(incoming.type)) {
                merged.type = incoming.type;
            }
        }
        return merged;
    }

    public static boolean isAllowedType(BogeyRenderedType type) {
        if (type == null) {
            return false;
        }
        for (ResourceLocation id : SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS) {
            BogeyEntry entry = BogeyMenuManager.getEntry(id);
            if (entry != null && entry.type().equals(type.type())) {
                return true;
            }
        }
        return false;
    }

    private static List<BogeyEntry> allowedEntries(boolean inverted) {
        List<BogeyEntry> entries = new ArrayList<>();
        for (ResourceLocation id : SimurailSurvivalBogeyRules.ALLOWED_ENTRY_IDS) {
            BogeyEntry entry = BogeyMenuManager.getEntry(id);
            if (entry == null) {
                continue;
            }
            if (inverted && !entry.type().invertible()) {
                continue;
            }
            entries.add(entry);
        }
        return entries;
    }

    private SimurailSurvivalBogeyGlue() {
    }
}

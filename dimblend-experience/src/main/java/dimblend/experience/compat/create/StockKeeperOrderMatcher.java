package dimblend.experience.compat.create;

import com.simibubi.create.content.logistics.BigItemStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 仓库管理员订单行与服务端库存快照的宽松匹配。
 *
 * <p>Create 的 {@code InventorySummary#getCountOf} 只认
 * {@link ItemStack#isSameItemSameComponents}（原型 + 组件补丁逐位相等）。第三方物品在
 * tooltip / 渲染路径上懒挂载组件、或客户端挂上 transient 组件后，订单里保存的副本与最新
 * 快照不再逐位相等，数量被夹到 0，订单格随即不再绘制、且 0 数量条目继续占着订单槽位。</p>
 *
 * <p>这里在严格匹配之外补一层有界放宽：</p>
 * <ul>
 *   <li>不可堆叠物品（{@code getMaxStackSize() == 1}，盔甲/工具/枪械等）：同一物品且
 *       六项身份组件（伤害、附魔、存储附魔、自定义名、盔甲纹饰、药水内容）逐一相等即视为
 *       同一件，容忍其余组件被懒写入；</li>
 *   <li>可堆叠物品：只容忍组件补丁的表示差异与 transient 组件（{@code codec() == null}，
 *       本就不进快照序列化），其余组件读值必须全等。</li>
 * </ul>
 *
 * <p>快照里出现多个匹配候选时返回空：宁可让订单按 Create 原逻辑归零，也不把玩家没点的
 * 那一件物品写进订单包。</p>
 *
 * <p>匹配算法本身只依赖 {@link StackView}。{@link ItemStack} 在纯 JUnit JVM 里需要
 * {@code Bootstrap}，而本模块测试运行时没有注册表，故算法与生产适配层分开，单测走假视图。</p>
 */
public final class StockKeeperOrderMatcher {

    private static final ResourceLocation DAMAGE = ResourceLocation.fromNamespaceAndPath("minecraft", "damage");
    private static final ResourceLocation ENCHANTMENTS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "enchantments");
    private static final ResourceLocation STORED_ENCHANTMENTS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "stored_enchantments");
    private static final ResourceLocation CUSTOM_NAME =
            ResourceLocation.fromNamespaceAndPath("minecraft", "custom_name");
    private static final ResourceLocation TRIM = ResourceLocation.fromNamespaceAndPath("minecraft", "trim");
    private static final ResourceLocation POTION_CONTENTS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "potion_contents");

    /** 不可堆叠物品的身份组件：这些相等才允许忽略其余组件的差异。 */
    private static final List<ResourceLocation> IDENTITY_COMPONENTS = List.of(
            DAMAGE, ENCHANTMENTS, STORED_ENCHANTMENTS, CUSTOM_NAME, TRIM, POTION_CONTENTS);

    private StockKeeperOrderMatcher() {
    }

    /** 唯一匹配结果：快照里的真实堆栈（数量归一为 1）与该堆栈当前可用数量。 */
    public record Match(ItemStack stack, int available) {
    }

    /**
     * 在快照里找与订单条目宽松匹配且唯一的堆栈。
     *
     * <p>调用方在 {@code summary.getCountOf(entry.stack) == 0} 时使用；返回的
     * {@link Match#stack()} 应回写进订单条目，保证后续 {@code getCountOf} 与
     * {@code PackageOrderWithCrafts} 都引用服务端快照里的那一份 ItemStack。</p>
     */
    public static Optional<Match> findUniqueMatch(ItemStack ordered, List<BigItemStack> snapshotStacks) {
        if (ordered == null || ordered.isEmpty() || snapshotStacks == null) {
            return Optional.empty();
        }
        StackView orderedView = viewOf(ordered);
        List<Candidate<BigItemStack>> candidates = new ArrayList<>(snapshotStacks.size());
        for (BigItemStack entry : snapshotStacks) {
            if (entry == null || entry.count <= 0 || entry.stack == null || entry.stack.isEmpty()) {
                continue;
            }
            candidates.add(new Candidate<>(entry, viewOf(entry.stack), entry.count));
        }
        return selectUnique(orderedView, candidates)
                .map(selection -> new Match(selection.payload().stack.copyWithCount(1), selection.available()));
    }

    /** 匹配所需的最小堆栈视图：物品身份、堆叠上限、非 transient 组件的读值。 */
    interface StackView {

        Object itemKey();

        int maxStackSize();

        Map<ResourceLocation, Object> visibleComponents();
    }

    record Candidate<T>(T payload, StackView view, int available) {
    }

    record Selection<T>(T payload, int available) {
    }

    static <T> Optional<Selection<T>> selectUnique(StackView ordered, List<Candidate<T>> candidates) {
        Selection<T> match = null;
        for (Candidate<T> candidate : candidates) {
            if (candidate.available() <= 0) {
                continue;
            }
            if (!compatible(ordered, candidate.view())) {
                continue;
            }
            if (match != null) {
                return Optional.empty();
            }
            match = new Selection<>(candidate.payload(), candidate.available());
        }
        return Optional.ofNullable(match);
    }

    static boolean compatible(StackView ordered, StackView candidate) {
        if (!Objects.equals(ordered.itemKey(), candidate.itemKey())) {
            return false;
        }
        if (ordered.maxStackSize() == 1 || candidate.maxStackSize() == 1) {
            Map<ResourceLocation, Object> left = ordered.visibleComponents();
            Map<ResourceLocation, Object> right = candidate.visibleComponents();
            for (ResourceLocation type : IDENTITY_COMPONENTS) {
                if (!Objects.equals(left.get(type), right.get(type))) {
                    return false;
                }
            }
            return true;
        }
        return ordered.visibleComponents().equals(candidate.visibleComponents());
    }

    private static StackView viewOf(ItemStack stack) {
        Map<ResourceLocation, Object> values = new HashMap<>();
        for (DataComponentType<?> type : stack.getComponents().keySet()) {
            if (type.isTransient()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (id != null) {
                values.put(id, stack.get(type));
            }
        }
        Object itemKey = stack.getItem();
        int maxStackSize = stack.getMaxStackSize();
        return new StackView() {

            @Override
            public Object itemKey() {
                return itemKey;
            }

            @Override
            public int maxStackSize() {
                return maxStackSize;
            }

            @Override
            public Map<ResourceLocation, Object> visibleComponents() {
                return values;
            }
        };
    }
}

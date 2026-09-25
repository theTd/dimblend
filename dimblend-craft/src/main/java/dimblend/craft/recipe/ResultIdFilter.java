package dimblend.craft.recipe;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.resources.ResourceLocation;

/**
 * 产物物品 ID 匹配器：精确 ID 集合 + ID 前缀集合。
 *
 * <p>用于「删除某个产物 / 某个 mod 自有产物」类删除规则，不关心配方 ID 与配方类型。</p>
 */
public record ResultIdFilter(Set<ResourceLocation> exactIds, List<String> idPrefixes) {

    public static ResultIdFilter exact(String... ids) {
        Set<ResourceLocation> parsed = Arrays.stream(ids)
                .map(ResourceLocation::parse)
                .collect(Collectors.toUnmodifiableSet());
        return new ResultIdFilter(parsed, List.of());
    }

    public static ResultIdFilter prefixed(String prefix) {
        return new ResultIdFilter(Set.of(), List.of(prefix));
    }

    public boolean matches(ResourceLocation resultId) {
        if (exactIds.contains(resultId)) {
            return true;
        }
        String id = resultId.toString();
        return idPrefixes.stream().anyMatch(id::startsWith);
    }
}
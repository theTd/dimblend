package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;

/**
 * Display name of a structure label: a translation from {@code assets/dimblend_spyglass/lang}, or a
 * readable form of the structure ID when the current language has no entry for it.
 */
final class StructureLabelNames {
    static final String KEY_PREFIX = DimBlendSpyglass.MODID + ".structure.";

    /**
     * {@code dimblend_spyglass.structure.<namespace>.<path>} with {@code /} in the path turned into
     * {@code .}, e.g. {@code terralith:underground/witch_hut} →
     * {@code dimblend_spyglass.structure.terralith.underground.witch_hut}.
     */
    static String translationKey(ResourceLocation id) {
        return KEY_PREFIX + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    /**
     * Title-cased path words, with {@code _} and {@code /} as separators and the namespace dropped:
     * {@code dungeons_arise:illager_fort} → {@code Illager Fort},
     * {@code terralith:underground/witch_hut} → {@code Underground Witch Hut}. Runs of separators
     * collapse into one space. A path without any word falls back to the full ID.
     */
    static String fallbackName(ResourceLocation id) {
        String path = id.getPath();
        StringBuilder name = new StringBuilder(path.length());
        boolean wordStart = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '/') {
                wordStart = true;
                continue;
            }
            if (wordStart) {
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(Character.toUpperCase(c));
                wordStart = false;
            } else {
                name.append(c);
            }
        }
        return name.isEmpty() ? id.toString() : name.toString();
    }

    /**
     * Called with {@code Language.getInstance()::has} / {@code ::getOrDefault}. The client language
     * loads {@code en_us} under the selected language, so a key that only exists in
     * {@code en_us.json} still resolves to its English name.
     */
    static String resolve(ResourceLocation id, Predicate<String> hasKey, UnaryOperator<String> translate) {
        String key = translationKey(id);
        return hasKey.test(key) ? translate.apply(key) : fallbackName(id);
    }

    private StructureLabelNames() {
    }
}

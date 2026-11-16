package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the checklist's mob-spawn-cap fix through Easy Mob Spawn Control's own config, so no
 * third-party file outside the game is edited by hand and no mixin reaches into Create.
 *
 * <p>Verified against {@code easy_mob_spawn_control-1.1.5.jar}: {@code CommonConfig.spawnCaps} is
 * the live map, {@code saveSpawnCaps} writes it, and {@code HandleServerAboutToStart} applies
 * {@code MobCategory.max} in memory without ever dereferencing its event object. That is the path
 * we want: the mod's {@code spawnRates} cancels {@code FinalizeSpawnEvent}, which keeps the caps
 * from filling and makes {@code NaturalSpawner.spawnForChunk} work harder.
 */
final class EasyMobSpawnControlBridge {
    private static final String CONFIG = "io.github.catomon.easymobspawncontrol.CommonConfig";
    private static final String HANDLER = "io.github.catomon.easymobspawncontrol.HandleServerAboutToStart";

    /** Returns false when the mod's classes or methods are absent, or applying the caps failed. */
    static boolean applyLimits() {
        try {
            Class<?> config = Class.forName(CONFIG);
            Map<String, Integer> updated = mergeTargetCaps(readCaps(config));
            config.getMethod("saveSpawnCaps", Map.class).invoke(null, updated);
            config.getMethod("reload").invoke(null);
            reapplyInMemory();
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            DimBlend.LOGGER.warn("Could not lower mob spawn caps through Easy Mob Spawn Control", failure);
            return false;
        }
    }

    /**
     * Merges checklist caps into {@code current}: lowers categories above our target (treating
     * negative sentinels as unset), preserves stricter player caps, and never overwrites an
     * existing {@code misc} cap with {@code -1}.
     */
    static Map<String, Integer> mergeTargetCaps(Map<String, Integer> current) {
        Map<String, Integer> updated = new LinkedHashMap<>(current);
        targets().forEach((category, target) -> {
            if (target < 0) {
                updated.putIfAbsent(category, target);
            } else {
                updated.merge(category, target,
                        (existing, wanted) -> existing < 0 ? wanted : Math.min(existing, wanted));
            }
        });
        return updated;
    }

    /** Config keys Easy Mob Spawn Control uses, with the cap this fix writes. */
    private static Map<String, Integer> targets() {
        Map<String, Integer> targets = new LinkedHashMap<>();
        targets.put("monster", PerfRules.MONSTER_SPAWN_CAP_MAX);
        targets.put("creature", PerfRules.CREATURE_SPAWN_CAP_RECOMMENDED);
        targets.put("ambient", 5);
        targets.put("axolotls", PerfRules.AXOLOTLS_SPAWN_CAP_RECOMMENDED);
        targets.put("underground_water_creature", PerfRules.UNDERGROUND_WATER_SPAWN_CAP_RECOMMENDED);
        targets.put("water_creature", PerfRules.WATER_CREATURE_SPAWN_CAP_RECOMMENDED);
        targets.put("water_ambient", 6);
        // -1 means "leave this category alone"; the mod ships it that way.
        targets.put("misc", -1);
        return targets;
    }

    private static Map<String, Integer> readCaps(Class<?> config) throws ReflectiveOperationException {
        Field field = config.getField("spawnCaps");
        if (!(field.get(null) instanceof Map<?, ?> caps)) {
            return Map.of();
        }
        Map<String, Integer> read = new LinkedHashMap<>();
        caps.forEach((key, cap) -> {
            if (key instanceof String category && cap instanceof Integer value) {
                read.put(category, value);
            }
        });
        return read;
    }

    /**
     * Re-applies the saved caps in memory. The handler only reflects {@code MobCategory.max} and
     * applies {@code CommonConfig.getSpawnCap(category)}, so passing a null event is safe; that is
     * exactly what the mod does at server start.
     */
    private static void reapplyInMemory() throws ReflectiveOperationException {
        Class<?> handler = Class.forName(HANDLER);
        for (Method method : handler.getDeclaredMethods()) {
            if (method.getName().equals("onServerAboutToStart") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                method.invoke(null, new Object[] {null});
                return;
            }
        }
        throw new NoSuchMethodException(HANDLER + ".onServerAboutToStart");
    }

    private EasyMobSpawnControlBridge() {
    }
}

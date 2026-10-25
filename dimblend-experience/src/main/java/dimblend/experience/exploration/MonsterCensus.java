package dimblend.experience.exploration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A12 diagnostic: count loaded {@link MobCategory#MONSTER} entities around a player,
 * grouped by entity type and by {@link Mob#getSpawnType()} (NeoForge stores the spawn
 * type on the mob and persists it as {@code neoforge:spawn_type}). Mobs loaded from
 * saves that predate that field, or created without {@code finalizeSpawn}, report
 * "unknown".
 */
public final class MonsterCensus {

    public static final int DEFAULT_RADIUS = 128;
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 512;
    public static final int TOP_TYPES = 15;

    /** Chat lines: header, up to {@link #TOP_TYPES} type rows, then totals. */
    public static List<String> report(ServerLevel level, Vec3 center, int radius) {
        double radiusSq = (double) radius * radius;
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box, mob ->
                mob.isAlive()
                        && mob.getType().getCategory() == MobCategory.MONSTER
                        && mob.distanceToSqr(center) <= radiusSq);

        Map<EntityType<?>, Map<MobSpawnType, Integer>> byType = new HashMap<>();
        for (Mob mob : mobs) {
            byType.computeIfAbsent(mob.getType(), ignored -> new HashMap<>())
                    .merge(mob.getSpawnType(), 1, Integer::sum);
        }

        List<Map.Entry<EntityType<?>, Map<MobSpawnType, Integer>>> rows = new ArrayList<>(byType.entrySet());
        rows.sort(Comparator
                .<Map.Entry<EntityType<?>, Map<MobSpawnType, Integer>>>comparingInt(row -> total(row.getValue()))
                .reversed()
                .thenComparing(row -> id(row.getKey())));

        List<String> lines = new ArrayList<>();
        lines.add("Monster census in " + level.dimension().location() + ": " + mobs.size()
                + " loaded MONSTER(s) within " + radius + " block(s)"
                + (rows.size() > TOP_TYPES ? " (top " + TOP_TYPES + " of " + rows.size() + " types)" : ""));
        int shown = Math.min(TOP_TYPES, rows.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<EntityType<?>, Map<MobSpawnType, Integer>> row = rows.get(i);
            lines.add((i + 1) + ". " + id(row.getKey()) + " x" + total(row.getValue())
                    + " - " + sources(row.getValue()));
        }

        Map<MobSpawnType, Integer> bySource = new HashMap<>();
        for (Map<MobSpawnType, Integer> counts : byType.values()) {
            counts.forEach((source, count) -> bySource.merge(source, count, Integer::sum));
        }
        lines.add("Totals: " + mobs.size() + " monster(s) - " + sources(bySource));
        return lines;
    }

    private static int total(Map<MobSpawnType, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        return total;
    }

    /** Spawn-source breakdown, highest count first, stable by label. */
    private static String sources(Map<MobSpawnType, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }
        List<Map.Entry<MobSpawnType, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator
                .<Map.Entry<MobSpawnType, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(entry -> label(entry.getKey())));
        StringBuilder text = new StringBuilder();
        for (Map.Entry<MobSpawnType, Integer> entry : entries) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(label(entry.getKey())).append(' ').append(entry.getValue());
        }
        return text.toString();
    }

    private static String label(MobSpawnType spawnType) {
        return spawnType == null ? "unknown" : spawnType.name();
    }

    private static String id(EntityType<?> type) {
        ResourceLocation key = EntityType.getKey(type);
        return key == null ? "unregistered" : key.toString();
    }

    private MonsterCensus() {
    }
}

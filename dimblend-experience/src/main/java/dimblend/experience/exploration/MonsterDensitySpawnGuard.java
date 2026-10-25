package dimblend.experience.exploration;

import java.util.Set;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * A12 rotating-dimension monster density guard.
 *
 * <p>NeoForge 21.1.249 fires {@link FinalizeSpawnEvent} for every vanilla path that
 * finalizes a mob: natural/chunk-generation spawning, both spawner flavours
 * ({@code BaseSpawner} and {@code TrialSpawner} call
 * {@code EventHooks.finalizeMobSpawnSpawner} even when the spawn data carries extra NBT),
 * structure pieces/templates, patrols, reinforcements and jockeys. Player-caused spawn
 * types (including {@code /summon}'s COMMAND) fire it too but are excluded by
 * {@link MonsterSpawnDensity#isCovered}.
 *
 * <p>The event runs at {@link EventPriority#LOWEST} so another mod cannot re-enable a
 * spawn after this decision. Both {@code setCanceled} (skip finalize side effects such
 * as a zombie's jockey chicken) and {@code setSpawnCancelled} (block the entity from
 * entering the level, including worldgen) are set, matching {@link SafeZoneSpawnGuard}.
 *
 * <p>Vanilla bypass: mob conversions create the new mob without
 * {@code finalizeSpawn}; CONVERSION is never suppressed, so the bypass is intentional.
 * A mod that adds a mob directly without calling {@code finalizeSpawn} also bypasses
 * this guard; vanilla has no such covered path (spawn eggs, buckets, commands and
 * datapack functions all go through {@code EntityType.create} or SummonCommand and do
 * fire the event with their own spawn type).
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class MonsterDensitySpawnGuard {

    /** The boss tag is the primary source of truth; these three are explicit per spec. */
    private static final Set<EntityType<?>> EXPLICIT_BOSSES = Set.of(
            EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.ELDER_GUARDIAN);

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        double density = Config.ROTATING_MONSTER_DENSITY.get();
        if (density >= 1.0D) {
            return;
        }
        Mob mob = event.getEntity();
        if (mob.getType().getCategory() != MobCategory.MONSTER
                || !MonsterSpawnDensity.isCovered(event.getSpawnType())) {
            return;
        }
        if (!RotatingDimension.is(event.getLevel())) {
            return;
        }
        if (MonsterSpawnDensity.shouldCancel(MobCategory.MONSTER, event.getSpawnType(), isBoss(mob.getType()),
                true, density, event.getLevel().getRandom().nextDouble())) {
            event.setCanceled(true);
            event.setSpawnCancelled(true);
        }
    }

    private static boolean isBoss(EntityType<?> type) {
        return EXPLICIT_BOSSES.contains(type) || type.is(Tags.EntityTypes.BOSSES);
    }

    private MonsterDensitySpawnGuard() {
    }
}

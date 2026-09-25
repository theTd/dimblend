package dimblend.compat;

import dimblend.DimBlendRegistries;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * Voidscape's {@code voidscape:nether} biome JSON spawns zombified piglins and
 * zoglins because Voidscape's own dimension is {@code piglin_safe: false}.
 * DimBlend's rotating dimension is piglin-safe, so those natural spawns are
 * swapped to piglin / hoglin at finalize time. Vanilla never registers
 * spawn placement for zoglin, so rotating also rejects mid-air zoglin
 * attempts ({@link #onSpawnPlacementCheck}) before that swap. Vanilla nether
 * biomes already spawn both living forms and are left alone.
 */
public final class VoidscapeNetherDezombify {
    private VoidscapeNetherDezombify() {
    }

    /**
     * Zoglins have no vanilla spawn placement. Block mid-air natural spawns in
     * rotating Voidscape nether before {@link #onFinalizeSpawn} swaps them to hoglins.
     */
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntityType());
        if (type == null
                || !VoidscapeNetherDezombifyRules.needsGroundPlacement(type.getNamespace(), type.getPath())) {
            return;
        }
        if (!isVoidscapeNether(level.getBiome(event.getPos()))) {
            return;
        }
        if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(event.getLevel(), event.getPos(), event.getEntityType())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (event.isCanceled() || event.isSpawnCancelled()) {
            return;
        }
        if (event.getSpawnType() != MobSpawnType.NATURAL) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        Mob original = event.getEntity();
        if (!shouldReplace(level, original.getType(), BlockPos.containing(event.getX(), event.getY(), event.getZ()))) {
            return;
        }
        ResourceLocation replacementId = replacementId(BuiltInRegistries.ENTITY_TYPE.getKey(original.getType()));
        if (replacementId == null) {
            return;
        }
        EntityType<?> replacementType = BuiltInRegistries.ENTITY_TYPE.getHolder(replacementId)
                .map(Holder::value)
                .orElse(null);
        if (replacementType == null) {
            return;
        }
        Entity created = replacementType.create(level);
        if (!(created instanceof Mob replacement)) {
            if (created != null) {
                created.discard();
            }
            return;
        }
        replacement.moveTo(event.getX(), event.getY(), event.getZ(), original.getYRot(), original.getXRot());
        EventHooks.finalizeMobSpawn(
                replacement,
                event.getLevel(),
                event.getDifficulty(),
                MobSpawnType.NATURAL,
                null
        );
        if (replacement.isSpawnCancelled() || !event.getLevel().addFreshEntity(replacement)) {
            replacement.discard();
            return;
        }
        event.setCanceled(true);
        event.setSpawnCancelled(true);
    }

    public static boolean shouldReplace(ServerLevel level, EntityType<?> type, BlockPos pos) {
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        if (!level.dimensionType().piglinSafe()) {
            return false;
        }
        if (replacementId(BuiltInRegistries.ENTITY_TYPE.getKey(type)) == null) {
            return false;
        }
        return isVoidscapeNether(level.getBiome(pos));
    }

    public static boolean isVoidscapeNether(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(key -> VoidscapeNetherDezombifyRules.isVoidscapeNether(
                        key.location().getNamespace(), key.location().getPath()))
                .orElse(false);
    }

    @Nullable
    public static ResourceLocation replacementId(@Nullable ResourceLocation type) {
        if (type == null) {
            return null;
        }
        String path = VoidscapeNetherDezombifyRules.replacementPath(type.getNamespace(), type.getPath());
        return path == null ? null : ResourceLocation.withDefaultNamespace(path);
    }
}

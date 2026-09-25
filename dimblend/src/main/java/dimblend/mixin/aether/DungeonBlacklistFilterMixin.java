package dimblend.mixin.aether;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aether's dungeon blacklist walks {@code ServerLevel.structureManager()}, so
 * {@code getStructureAt} does {@code ServerChunkCache.getChunk} from a worldgen worker
 * ({@code supplyAsync(main).join()}). That parks the worldgen mailbox on a FULL/structure
 * future that needs the same mailbox — chunkgen rate 0, workers idle, FEATURES/LIGHT/FULL
 * futures stuck. Bind the query to {@code forWorldGenRegion} so reads stay in the
 * generation cache (STRUCTURE_STARTS already present).
 */
@Mixin(targets = "com.aetherteam.aether.world.placementmodifier.DungeonBlacklistFilter")
public abstract class DungeonBlacklistFilterMixin {
    private static final TagKey<Structure> AETHER_DUNGEONS =
            TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath("aether", "dungeons"));

    @Inject(method = "shouldPlace", at = @At("HEAD"), cancellable = true)
    private void dimblend$noSyncLoad(
            PlacementContext context,
            RandomSource random,
            BlockPos pos,
            CallbackInfoReturnable<Boolean> cir
    ) {
        WorldGenLevel level = context.getLevel();
        if (!(level instanceof WorldGenRegion region)) {
            cir.setReturnValue(false);
            return;
        }
        StructureManager structures = region.getLevel().structureManager().forWorldGenRegion(region);
        Registry<Structure> registry = region.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Holder<Structure> dungeon : registry.getOrCreateTag(AETHER_DUNGEONS)) {
            try {
                StructureStart start = structures.getStructureAt(pos, dungeon.value());
                if (start.isValid()) {
                    cir.setReturnValue(false);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Reference points at a start chunk outside this WorldGenRegion.
            }
        }
        cir.setReturnValue(true);
    }
}

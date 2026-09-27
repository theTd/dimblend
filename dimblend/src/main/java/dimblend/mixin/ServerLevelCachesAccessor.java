package dimblend.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Per-level entity manager and structure presence cache, reached by region purge. */
@Mixin(ServerLevel.class)
public interface ServerLevelCachesAccessor {

    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> dimblend$getEntityManager();

    @Accessor("structureCheck")
    StructureCheck dimblend$getStructureCheck();
}

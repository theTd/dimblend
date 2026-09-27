package dimblend.mixin;

import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Structure presence memo per chunk. Vanilla fills it for every chunk that reaches structure
 * starts and never evicts; all mutation happens on the server thread.
 */
@Mixin(StructureCheck.class)
public interface StructureCheckAccessor {

    @Accessor("loadedChunks")
    Long2ObjectMap<Object2IntMap<Structure>> dimblend$getLoadedChunks();

    @Accessor("featureChecks")
    Map<Structure, Long2BooleanMap> dimblend$getFeatureChecks();
}

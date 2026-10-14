package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The same connection ID used by Sable's dependency traversal, without loading an absent partner. */
@Mixin(AutomaticCouplerBlockEntity.class)
public interface AutomaticCouplerPartnerAccessor {
    @Accessor(value = "partnerSubLevelID", remap = false)
    UUID dimblend$getPartnerSubLevelId();
}

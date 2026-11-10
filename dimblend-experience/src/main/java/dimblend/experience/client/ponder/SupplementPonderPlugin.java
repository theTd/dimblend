package dimblend.experience.client.ponder;

import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;
import dimblend.experience.DimBlend;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Registered on the client only when Create and Simurail are installed. */
public final class SupplementPonderPlugin implements PonderPlugin {
    @Override
    public String getModId() {
        return DimBlend.MODID;
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.addToTag(AllCreatePonderTags.DISPLAY_SOURCES)
                .add(ResourceLocation.fromNamespaceAndPath("simurail", "physics_bogey"));
    }
}

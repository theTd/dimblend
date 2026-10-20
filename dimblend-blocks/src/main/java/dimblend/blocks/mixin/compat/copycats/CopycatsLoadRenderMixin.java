package dimblend.blocks.mixin.compat.copycats;

import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import dimblend.blocks.client.CopycatLoadRenderRefresh;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only refresh for ordinary and creative Copycats+ slabs and beams. */
@Mixin(value = {CCCopycatBlockEntity.class, MultiStateCopycatBlockEntity.class}, remap = false)
public abstract class CopycatsLoadRenderMixin {
    @Inject(method = "onLoad", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblendblocks$refreshInitialCopycatMesh(CallbackInfo ci) {
        CopycatLoadRenderRefresh.schedule((BlockEntity) (Object) this);
    }
}

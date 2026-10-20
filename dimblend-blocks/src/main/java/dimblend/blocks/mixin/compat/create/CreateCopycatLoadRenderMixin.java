package dimblend.blocks.mixin.compat.create;

import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import dimblend.blocks.client.CopycatLoadRenderRefresh;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only: Create's onLoad requests model data but does not invalidate the first mesh. */
@Mixin(value = CopycatBlockEntity.class, remap = false)
public abstract class CreateCopycatLoadRenderMixin {
    @Inject(method = "onLoad", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblendblocks$refreshInitialCopycatMesh(CallbackInfo ci) {
        CopycatLoadRenderRefresh.schedule((BlockEntity) (Object) this);
    }
}

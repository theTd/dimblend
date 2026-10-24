package dimblend.experience.mixin.compat.connected;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.redstone.link.LinkHandler;
import dimblend.experience.compat.connected.LinkedTransmitterInteraction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LinkHandler.class)
public abstract class LinkHandlerEventHitMixin {

    @WrapOperation(
            method = "onBlockActivated",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/utility/RaycastHelper;rayTraceRange(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;D)Lnet/minecraft/world/phys/BlockHitResult;"),
            require = 1)
    private static BlockHitResult dimblend$usePacketHit(Level level, Player player, double range,
            Operation<BlockHitResult> original,
            @Local(argsOnly = true) PlayerInteractEvent.RightClickBlock event) {
        if (LinkedTransmitterInteraction.supports(event.getLevel().getBlockState(event.getPos()), event.getEntity())) {
            BlockHitResult hit = LinkedTransmitterInteraction.packetHit(event);
            if (hit != null) {
                return hit;
            }
        }
        return original.call(level, player, range);
    }
}

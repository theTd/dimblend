package dimblend.experience.compat.connected;

import com.hlysine.create_connected.content.linkedtransmitter.LinkedTransmitterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

public final class LinkedTransmitterInteraction {

    public static boolean supports(BlockState state, Player player) {
        return state.getBlock() instanceof LinkedTransmitterBlock && !(player instanceof FakePlayer);
    }

    @Nullable
    public static BlockHitResult packetHit(PlayerInteractEvent.RightClickBlock event) {
        return packetHit(event.getHitVec(), event.getPos());
    }

    @Nullable
    public static BlockHitResult packetHit(BlockHitResult hit, BlockPos expectedPos) {
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(expectedPos)) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        Vec3 location = hit.getLocation();
        return new BlockHitResult(
                new Vec3(
                        pos.getX() + (double) (float) (location.x - pos.getX()),
                        pos.getY() + (double) (float) (location.y - pos.getY()),
                        pos.getZ() + (double) (float) (location.z - pos.getZ())),
                hit.getDirection(),
                pos,
                hit.isInside());
    }

    private LinkedTransmitterInteraction() {
    }
}

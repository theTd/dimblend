package dimblend.experience.compat.connected;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LinkedTransmitterInteractionTest {

    @Test
    void packetHitUsesRelativeFloatCoordinatesAndIsIdempotent() {
        BlockPos pos = new BlockPos(20_481_032, 129, 20_509_704);
        Vec3 delta = new Vec3(0.843750123456789, 0.068749876543211, 0.343750987654321);
        BlockHitResult original = new BlockHitResult(
                new Vec3(pos.getX() + delta.x, pos.getY() + delta.y, pos.getZ() + delta.z),
                Direction.UP,
                pos,
                false);

        BlockHitResult quantized = LinkedTransmitterInteraction.packetHit(original, pos);
        assertNotNull(quantized);
        assertEquals(pos.getX() + (double) (float) delta.x, quantized.getLocation().x);
        assertEquals(pos.getY() + (double) (float) delta.y, quantized.getLocation().y);
        assertEquals(pos.getZ() + (double) (float) delta.z, quantized.getLocation().z);
        assertEquals(Direction.UP, quantized.getDirection());
        assertEquals(false, quantized.isInside());

        BlockHitResult twice = LinkedTransmitterInteraction.packetHit(quantized, pos);
        assertNotNull(twice);
        assertEquals(quantized.getLocation(), twice.getLocation());
    }

    @Test
    void packetHitRejectsAnotherBlockAndMisses() {
        BlockPos pos = new BlockPos(4, 70, -9);
        BlockHitResult hit = new BlockHitResult(new Vec3(4.5, 70.5, -8.5), Direction.UP, pos, false);
        assertNull(LinkedTransmitterInteraction.packetHit(hit, pos.north()));
        assertNull(LinkedTransmitterInteraction.packetHit(
                BlockHitResult.miss(hit.getLocation(), Direction.UP, pos), pos));
    }
}

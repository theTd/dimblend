package dimblend.experience.compat.tavern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.junit.jupiter.api.Test;

/** 酒桶龙头只能挂在侧壁出酒口（对照 {@code TapBlock#isValidConnection}）。 */
class TapAttachmentRulesTest {

    @Test
    void fourWallCentresAreSpouts() {
        assertTrue(TapAttachmentRules.isSpoutCell(AttachFace.WALL, 1, Direction.NORTH));
        assertTrue(TapAttachmentRules.isSpoutCell(AttachFace.WALL, 7, Direction.SOUTH));
        assertTrue(TapAttachmentRules.isSpoutCell(AttachFace.WALL, 3, Direction.WEST));
        assertTrue(TapAttachmentRules.isSpoutCell(AttachFace.WALL, 5, Direction.EAST));
    }

    @Test
    void floorAndCeilingNeverHaveSpouts() {
        assertFalse(TapAttachmentRules.isSpoutCell(AttachFace.FLOOR, 4, Direction.NORTH), "落地桶没有出酒口");
        assertFalse(TapAttachmentRules.isSpoutCell(AttachFace.CEILING, 4, Direction.NORTH));
    }

    @Test
    void otherIndicesOnTheWallAreNotSpouts() {
        for (int index : new int[] {0, 2, 4, 6, 8}) {
            assertFalse(TapAttachmentRules.isSpoutCell(AttachFace.WALL, index, Direction.NORTH),
                    "index " + index + " 不是正中格");
        }
    }

    @Test
    void tapMustFaceTheSameDirectionAsTheBarrel() {
        assertTrue(TapAttachmentRules.isValidConnection(AttachFace.WALL, 1, Direction.NORTH, Direction.NORTH));
        assertFalse(TapAttachmentRules.isValidConnection(AttachFace.WALL, 1, Direction.NORTH, Direction.EAST));
        assertFalse(TapAttachmentRules.isValidConnection(AttachFace.WALL, 7, Direction.SOUTH, Direction.NORTH));
        assertTrue(TapAttachmentRules.isValidConnection(AttachFace.WALL, 5, Direction.EAST, Direction.EAST));
        assertFalse(TapAttachmentRules.isValidConnection(AttachFace.FLOOR, 4, Direction.NORTH, Direction.NORTH));
    }

    @Test
    void tapSitsTwoBlocksOutOnTheMiddleLayer() {
        BlockPos origin = new BlockPos(10, 64, 20);
        assertEquals(new BlockPos(10, 65, 18), TapAttachmentRules.tapPos(origin, Direction.NORTH));
        assertEquals(new BlockPos(10, 65, 22), TapAttachmentRules.tapPos(origin, Direction.SOUTH));
        assertEquals(new BlockPos(8, 65, 20), TapAttachmentRules.tapPos(origin, Direction.WEST));
        assertEquals(new BlockPos(12, 65, 20), TapAttachmentRules.tapPos(origin, Direction.EAST));
    }
}

package dimblend.fluid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.TestSourceTree;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class NetherLavaFlowTest {
    @Test
    void netherAndVoidscapeLanesFlowAtNetherSpeed() {
        assertTrue(NetherLavaFlow.isNetherSpeedLane("nether"));
        assertTrue(NetherLavaFlow.isNetherSpeedLane("voidscape"));
    }

    @Test
    void otherLanesKeepOverworldLavaSpeed() {
        for (String lane : new String[]{"surface", "underground", "end", "twilight", "starlight", "aether", "deeperdarker", "mod"}) {
            assertFalse(NetherLavaFlow.isNetherSpeedLane(lane), lane);
        }
    }

    @Test
    void enterExitRestoresOuterColumn() {
        Integer none = NetherLavaFlow.enter(new BlockPos(5, 0, 0));
        assertEquals(null, none);
        Integer outer = NetherLavaFlow.enter(new BlockPos(9, 0, 0));
        assertEquals(5, outer);
        NetherLavaFlow.exit(outer);
        NetherLavaFlow.exit(none);
        assertEquals(null, NetherLavaFlow.enter(new BlockPos(1, 0, 0)));
        NetherLavaFlow.exit(null);
    }

    @Test
    void lavaMixinsAreRegisteredAndCoverEveryUltrawarmReader() throws Exception {
        String json = Files.readString(TestSourceTree.mainFile("resources/dimblend.mixins.json"), StandardCharsets.UTF_8);
        for (String mixin : new String[]{"FlowingFluidTickMixin", "LavaFluidUltrawarmMixin", "LiquidBlockLavaFlowMixin"}) {
            assertTrue(json.contains("\"" + mixin + "\""), mixin);
        }
        String lava = Files.readString(
                TestSourceTree.mainFile("java/dimblend/mixin/LavaFluidUltrawarmMixin.java"), StandardCharsets.UTF_8);
        for (String reader : new String[]{"getSlopeFindDistance", "getDropOff", "getTickDelay"}) {
            assertTrue(lava.contains("\"" + reader + "\""), reader);
        }
    }
}

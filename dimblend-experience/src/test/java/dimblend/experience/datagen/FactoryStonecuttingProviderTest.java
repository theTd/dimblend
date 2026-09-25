package dimblend.experience.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 工厂切石机配方范围锁：42 个纯装饰方块，7 种风扇必须排除。
 *
 * <p>纯内存断言，不进游戏；上游 factory_blocks 增删方块时此测试会先红，提醒同步
 * {@link FactoryStonecuttingBlocks} 并重跑 runData。
 */
class FactoryStonecuttingProviderTest {
    private static final Set<String> EXCLUDED_FANS = Set.of(
            "fan", "fan_on", "fan_four", "fan_four_on",
            "fan_malfunction", "fan_malfunction_on", "medium_fan");

    @Test
    void decorativeBlockCountIs42() {
        assertEquals(42, FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.size());
    }

    @Test
    void noDuplicates() {
        assertEquals(
                new HashSet<>(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS).size(),
                FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.size());
    }

    @Test
    void fansAreExcluded() {
        for (String fan : EXCLUDED_FANS) {
            assertTrue(!FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.contains(fan), fan + " must be excluded");
        }
    }

    @Test
    void fanSideIsIncluded() {
        // fan_side 虽名字带 fan，但是 Type.base 纯装饰（光滑金属），应包含
        assertTrue(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.contains("fan_side"));
    }
}

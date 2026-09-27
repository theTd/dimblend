package dimblend.experience.compat.simulated;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 同类识别（纯函数，不启动游戏、不碰注册表）。
 *
 * <p>注册表明细以 bundled jar 实测为准：16 色
 * {@code simulated:<color>_portable_engine}（blockstates/models/loot/lang 全只有
 * 带颜色版本），裸 {@code simulated:portable_engine} 是 BlockEntityType id、
 * 不是方块——识别以后缀为准恰好容下 16 色、排除裸名与其他 simulated 方块。</p>
 */
class PortableEngineExclusivityTest {

    @Test
    void matchesAllSixteenDyedEngines() {
        for (String color : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            assertTrue(PortableEngineExclusivity.isPortableEngineId("simulated", color + "_portable_engine"),
                    "漏识别: " + color);
        }
    }

    @Test
    void rejectsBareIdAssemblerAndAssembly() {
        // 裸 portable_engine（BET id）、physics_assembler、engine_assembly 都不是 16 色引擎
        assertFalse(PortableEngineExclusivity.isPortableEngineId("simulated", "portable_engine"));
        assertFalse(PortableEngineExclusivity.isPortableEngineId("simulated", "physics_assembler"));
        assertFalse(PortableEngineExclusivity.isPortableEngineId("simulated", "engine_assembly"));
        // 其他命名空间同名路径也不中
        assertFalse(PortableEngineExclusivity.isPortableEngineId("create", "red_portable_engine"));
        assertFalse(PortableEngineExclusivity.isPortableEngineId("minecraft", "stone"));
    }

    @Test
    void wholeNetworkConvergesToOne() {
        // 整网唯一：N≥2 一次收敛只剩 1 台——幸存者之外全拆，幸存者本身不在拆除表里
        assertEquals(List.of("a", "c"), PortableEngineExclusivity.victimsKeepingOne(List.of("a", "b", "c"), 1));
        assertEquals(List.of("b"), PortableEngineExclusivity.victimsKeepingOne(List.of("a", "b"), 0));
        List<Integer> sixteen = IntStream.range(0, 16).boxed().toList();
        List<Integer> victims = PortableEngineExclusivity.victimsKeepingOne(sixteen, 15);
        assertEquals(15, victims.size());
        assertFalse(victims.contains(15));
    }

    @Test
    void singleOrNoEngineIsNeverDestroyed() {
        assertTrue(PortableEngineExclusivity.victimsKeepingOne(List.of("a"), 0).isEmpty());
        assertTrue(PortableEngineExclusivity.victimsKeepingOne(List.<String>of(), 0).isEmpty());
    }

    @Test
    void intervalConstantDocumentsRemovedPolling() {
        // 8 秒轮询已砍：常量只留文档/换算引用，值恒 160 tick；若将来重加轮询，
        // 此单测会提醒同步更新 PortableEngineExclusivity 的注释与实现
        assertEquals(160, PortableEngineExclusivity.INTERVAL_TICKS);
    }
}

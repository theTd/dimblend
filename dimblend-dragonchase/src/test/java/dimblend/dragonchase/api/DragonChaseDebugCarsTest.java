package dimblend.dragonchase.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dimblend.dragonchase.structure.CarKind;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DragonChaseDebugCarsTest {

    @Test
    void debugCarIdsIncludesEveryNonNestKindAndExcludesNest() {
        List<String> ids = DragonChaseDebugCars.debugCarIds();
        assertFalse(ids.contains("nest"), "龙巢车厢绝不能开放为创造调试车厢");
        assertEquals(CarKind.values().length - 1, ids.size());
        for (CarKind kind : CarKind.values()) {
            String id = kind.name().toLowerCase(Locale.ROOT);
            assertEquals(kind != CarKind.NEST, ids.contains(id), kind + " 在 debugCarIds 中的存在性不符");
        }
    }
}

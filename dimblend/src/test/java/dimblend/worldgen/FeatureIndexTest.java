package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class FeatureIndexTest {
    @Test
    void identityHitIsAuthoritative() {
        List<String> features = List.of("a", "b", "c");
        assertEquals(1, FeatureIndex.resolve(features, value -> "b".equals(value) ? 1 : -1, "b"));
    }

    @Test
    void lengthThreeIdentityMissUsesEqualCopy() {
        String kept = new String("feature");
        List<String> features = List.of("x", "y", kept);
        String incoming = new String("feature");
        ToIntFunction<String> identity = value -> value == kept ? 2 : -1;
        assertEquals(-1, identity.applyAsInt(incoming));
        assertEquals(2, FeatureIndex.resolve(features, identity, incoming));
    }

    @Test
    void outOfRangeIdentityFallsBackToEquals() {
        List<String> features = List.of("a", "b");
        assertEquals(0, FeatureIndex.resolve(features, value -> 9, "a"));
    }

    @Test
    void unknownFeatureStaysNegative() {
        assertEquals(-1, FeatureIndex.resolve(List.of("a", "b", "c"), value -> -1, "d"));
    }
}

package dimblend.spyglass.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The provider registry: order, duplicate ids, provider failures and marker validation. */
class HudWorldMarkersTest {
    @AfterEach
    void clearRegistry() {
        HudWorldMarkers.clear();
    }

    @Test
    void markersKeepProviderOrder() {
        HudWorldMarkers.register(() -> List.of(marker("photo_origin", 1.0, 2.0, 3.0)));
        HudWorldMarkers.register(() -> List.of(marker("second", 4.0, 5.0, 6.0)));

        List<HudWorldMarkers.Marker> markers = HudWorldMarkers.current();

        assertEquals(List.of("photo_origin", "second"),
                markers.stream().map(HudWorldMarkers.Marker::id).toList());
    }

    @Test
    void aDuplicateIdKeepsTheFirstMarker() {
        HudWorldMarkers.register(() -> List.of(marker("photo_origin", 1.0, 2.0, 3.0)));
        HudWorldMarkers.register(() -> List.of(marker("photo_origin", 90.0, 91.0, 92.0)));

        List<HudWorldMarkers.Marker> markers = HudWorldMarkers.current();

        assertEquals(1, markers.size());
        assertEquals(1.0, markers.get(0).x());
    }

    @Test
    void aThrowingProviderDoesNotBreakTheFrame() {
        HudWorldMarkers.register(() -> {
            throw new IllegalStateException("broken integration");
        });
        HudWorldMarkers.register(() -> List.of(marker("photo_origin", 1.0, 2.0, 3.0)));

        List<HudWorldMarkers.Marker> markers = HudWorldMarkers.current();

        assertEquals(1, markers.size());
        assertEquals("photo_origin", markers.get(0).id());
    }

    @Test
    void unregisterRemovesOnlyThatProvider() {
        HudWorldMarkers.Provider first = () -> List.of(marker("first", 1.0, 2.0, 3.0));
        HudWorldMarkers.register(first);
        HudWorldMarkers.register(() -> List.of(marker("second", 4.0, 5.0, 6.0)));

        HudWorldMarkers.unregister(first);

        assertEquals(List.of("second"),
                HudWorldMarkers.current().stream().map(HudWorldMarkers.Marker::id).toList());
    }

    @Test
    void theSnapshotIsImmutable() {
        HudWorldMarkers.register(() -> List.of(marker("photo_origin", 1.0, 2.0, 3.0)));
        List<HudWorldMarkers.Marker> markers = HudWorldMarkers.current();

        assertThrows(UnsupportedOperationException.class,
                () -> markers.add(marker("other", 0.0, 0.0, 0.0)));
        assertTrue(markers.get(0).guideWithArrowAndDistance());
    }

    @Test
    void aMarkerNeedsAnIdGlyphAndFinitePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new HudWorldMarkers.Marker("", "@", 0.0, 0.0, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HudWorldMarkers.Marker("id", "", 0.0, 0.0, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new HudWorldMarkers.Marker("id", "@", Double.NaN, 0.0, 0.0, false));
    }

    @Test
    void anEmptyRegistryProducesAnEmptySnapshot() {
        assertTrue(HudWorldMarkers.current().isEmpty());
    }

    private static HudWorldMarkers.Marker marker(String id, double x, double y, double z) {
        return new HudWorldMarkers.Marker(id, "@", x, y, z, true);
    }
}

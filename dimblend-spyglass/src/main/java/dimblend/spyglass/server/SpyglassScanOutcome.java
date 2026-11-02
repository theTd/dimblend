package dimblend.spyglass.server;

import java.util.List;

/**
 * Everything one scan produced, computed on the scan executor and handed to the tick thread.
 *
 * @param limited       nearest-first structures after the snapshot caps, read and predicted together
 * @param extraction    encoded shapes for the structures that have real blocks on disk
 * @param previewShapes encoded template previews for the nearest predicted structures (M2)
 * @param readErrors    shared read-failure counts of the start scan and the shape extraction
 * @param rawOutlines   number of outlines found before the limiter, for the DEBUG summary
 * @param prediction    how much seed-based prediction this scan did, for the DEBUG summary
 */
record SpyglassScanOutcome(
        SpyglassSnapshotLimiter.LimitResult limited,
        StructureShapeExtractor.Extraction extraction,
        List<EncodedStructureShape> previewShapes,
        RegionScanResult.Counts readErrors,
        int rawOutlines,
        StructurePredictor.Prediction prediction) {
}

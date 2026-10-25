package dimblend.spyglass.server;

/**
 * Everything one scan produced, computed on the scan executor and handed to the tick thread.
 *
 * @param limited   nearest-first structures after the snapshot caps
 * @param extraction encoded shapes for the structures that have real blocks on disk
 * @param readErrors shared read-failure counts of the start scan and the shape extraction
 * @param rawOutlines number of outlines found before the limiter, for the DEBUG summary
 */
record SpyglassScanOutcome(
        SpyglassSnapshotLimiter.LimitResult limited,
        StructureShapeExtractor.Extraction extraction,
        RegionScanResult.Counts readErrors,
        int rawOutlines) {
}

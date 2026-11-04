package dimblend.spyglass.server;

/**
 * Everything one scan produced, computed on the scan executor and handed to the tick thread.
 *
 * @param limited     nearest-first structures after the snapshot caps, read and predicted together
 * @param readErrors  shared read-failure counts of the region scan
 * @param rawOutlines number of outlines found before the limiter, for the DEBUG summary
 * @param prediction  how much seed-based prediction this scan did, for the DEBUG summary
 */
record SpyglassScanOutcome(
        SpyglassSnapshotLimiter.LimitResult limited,
        RegionScanResult.Counts readErrors,
        int rawOutlines,
        StructurePredictor.Prediction prediction) {
}

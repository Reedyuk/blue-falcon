package dev.bluefalcon.plugins.metrics

/**
 * Minimal, dependency-free cumulative histogram for recording BLE operation latencies.
 *
 * Bucket boundaries are configurable; [defaultBleLatencyBuckets] is tuned for typical BLE
 * connect/read/write latencies (fast sub-10ms operations up through multi-second stalls/retries).
 *
 * Every bucket counts values less-than-or-equal-to its boundary (cumulative, in the style of
 * Prometheus/OpenTelemetry histograms), plus one implicit `+Inf` bucket (keyed by [Long.MAX_VALUE]
 * in [snapshotCounts]) for values exceeding the largest configured boundary.
 *
 * This is a lightweight, best-effort counter intended for cheap in-process visibility (e.g. a
 * debug screen). [record] is not linearizable under highly concurrent access from multiple
 * threads on native/JVM targets; counts may very rarely under-count a handful of concurrent
 * updates, which is an acceptable trade-off for a zero-dependency aggregate metric. Callers
 * needing exact counts should record via a [MetricsExporter] instead, which observes every
 * [OperationMetric] individually.
 */
class Histogram(boundariesMillis: List<Long> = defaultBleLatencyBuckets) {

    private val boundaries: List<Long> = boundariesMillis.sorted()
    private val bucketCounts = LongArray(boundaries.size + 1)

    /**
     * Records [valueMillis] into the smallest bucket whose boundary is `>= valueMillis`, or the
     * overflow (`+Inf`) bucket if it exceeds every configured boundary.
     */
    fun record(valueMillis: Long) {
        val index = boundaries.indexOfFirst { valueMillis <= it }
        val bucket = if (index == -1) boundaries.size else index
        bucketCounts[bucket] = bucketCounts[bucket] + 1
    }

    /**
     * A snapshot of per-bucket counts, keyed by bucket upper bound. The overflow bucket is keyed
     * by [Long.MAX_VALUE].
     */
    fun snapshotCounts(): Map<Long, Long> {
        val result = LinkedHashMap<Long, Long>(boundaries.size + 1)
        boundaries.forEachIndexed { i, boundary -> result[boundary] = bucketCounts[i] }
        result[Long.MAX_VALUE] = bucketCounts[boundaries.size]
        return result
    }

    companion object {
        val defaultBleLatencyBuckets: List<Long> =
            listOf(5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000)
    }
}

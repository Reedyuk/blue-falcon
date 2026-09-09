package dev.bluefalcon.plugins.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class HistogramTest {

    @Test
    fun `records values into the correct cumulative bucket`() {
        val histogram = Histogram(boundariesMillis = listOf(10, 50, 100))

        histogram.record(5)
        histogram.record(10)
        histogram.record(42)
        histogram.record(500)

        val counts = histogram.snapshotCounts()
        assertEquals(2, counts[10]) // 5ms and 10ms both fall into the <=10 bucket
        assertEquals(1, counts[50]) // 42ms falls into the <=50 bucket
        assertEquals(0, counts[100])
        assertEquals(1, counts[Long.MAX_VALUE]) // 500ms overflows every configured boundary
    }

    @Test
    fun `default buckets are sorted ascending and cover typical BLE latencies`() {
        val boundaries = Histogram.defaultBleLatencyBuckets
        assertEquals(boundaries.sorted(), boundaries)
    }

    @Test
    fun `empty histogram reports zero for every bucket`() {
        val histogram = Histogram(boundariesMillis = listOf(10, 20))

        val counts = histogram.snapshotCounts()

        assertEquals(0, counts[10])
        assertEquals(0, counts[20])
        assertEquals(0, counts[Long.MAX_VALUE])
    }
}

package dev.bluefalcon.plugins.metrics

import dev.bluefalcon.core.plugin.BlueFalconOperationKind
import dev.bluefalcon.core.plugin.OperationTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MetricsPluginTest {

    @Test
    fun `records connect success and failure counts separately`() = runTest {
        val plugin = MetricsPlugin.create()

        plugin.onOperationCompleted(telemetry(BlueFalconOperationKind.CONNECT, success = true, durationMillis = 20))
        plugin.onOperationCompleted(telemetry(BlueFalconOperationKind.CONNECT, success = false, durationMillis = 5))
        plugin.onOperationCompleted(telemetry(BlueFalconOperationKind.CONNECT, success = true, durationMillis = 30))

        val snapshot = plugin.snapshot.value
        assertEquals(2, snapshot.connectSuccessCount)
        assertEquals(1, snapshot.connectFailureCount)
        // The latency histogram tracks every attempt's duration, success or failure.
        assertEquals(3, snapshot.connectLatencyHistogram.snapshotCounts().values.sum())
    }

    @Test
    fun `tracks bytes read and written only on success`() = runTest {
        val plugin = MetricsPlugin.create()

        plugin.onOperationCompleted(
            telemetry(BlueFalconOperationKind.READ, success = true, durationMillis = 8, byteCount = 20)
        )
        plugin.onOperationCompleted(
            telemetry(BlueFalconOperationKind.READ, success = false, durationMillis = 8, byteCount = null)
        )
        plugin.onOperationCompleted(
            telemetry(BlueFalconOperationKind.WRITE, success = true, durationMillis = 12, byteCount = 82)
        )

        val snapshot = plugin.snapshot.value
        assertEquals(20, snapshot.bytesRead)
        assertEquals(82, snapshot.bytesWritten)
        assertEquals(1, snapshot.readSuccessCount)
        assertEquals(1, snapshot.readFailureCount)
        assertEquals(1, snapshot.writeSuccessCount)
    }

    @Test
    fun `forwards every recorded operation to configured exporters with attempt count`() = runTest {
        val recorded = mutableListOf<OperationMetric>()
        val plugin = MetricsPlugin.create {
            exporters = listOf(object : MetricsExporter {
                override fun record(metric: OperationMetric) {
                    recorded += metric
                }
            })
        }

        plugin.onOperationCompleted(
            telemetry(BlueFalconOperationKind.WRITE, success = true, durationMillis = 15, attempts = 3)
        )

        assertEquals(1, recorded.size)
        assertEquals(3, recorded.single().attemptCount)
        assertTrue(recorded.single().success)
    }

    @Test
    fun `scan and disconnect operations update their own counters only`() = runTest {
        val plugin = MetricsPlugin.create()

        plugin.onOperationCompleted(telemetry(BlueFalconOperationKind.SCAN, success = true, durationMillis = 1, peripheralUuid = null))
        plugin.onOperationCompleted(telemetry(BlueFalconOperationKind.DISCONNECT, success = true, durationMillis = 2))

        val snapshot = plugin.snapshot.value
        assertEquals(1, snapshot.scanSuccessCount)
        assertEquals(1, snapshot.disconnectSuccessCount)
        assertEquals(0, snapshot.connectSuccessCount)
        assertEquals(0, snapshot.readSuccessCount)
        assertEquals(0, snapshot.writeSuccessCount)
    }

    private fun telemetry(
        operation: BlueFalconOperationKind,
        success: Boolean,
        durationMillis: Long,
        attempts: Int = 1,
        byteCount: Int? = null,
        peripheralUuid: String? = "peripheral-1"
    ) = OperationTelemetry(
        operation = operation,
        peripheralUuid = peripheralUuid,
        success = success,
        durationMillis = durationMillis,
        attempts = attempts,
        byteCount = byteCount
    )
}

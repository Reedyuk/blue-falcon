package dev.bluefalcon.plugins.metrics

import dev.bluefalcon.core.plugin.BlueFalconOperationKind

/**
 * The category of BLE operation an [OperationMetric] describes.
 *
 * An alias of [BlueFalconOperationKind], the generic operation-kind type that
 * [dev.bluefalcon.core.plugin.BlueFalconPlugin.onOperationCompleted] reports — kept as a distinct
 * public name in this module so consumers of the metrics plugin don't need to know that the
 * underlying instrumentation hook lives in `blue-falcon-core`.
 */
typealias MetricOperation = BlueFalconOperationKind

/**
 * One completed BLE operation, as observed by [MetricsPlugin] through
 * [dev.bluefalcon.core.plugin.BlueFalconPlugin.onOperationCompleted].
 *
 * @param peripheralUuid `null` for [MetricOperation.SCAN], which is not peripheral-scoped.
 * @param attemptCount total number of attempts made for this operation: 1 if no retry plugin is
 * installed or none was needed, greater than 1 if a `RetryCapable` plugin (e.g.
 * `blue-falcon-plugin-retry`) drove one or more retries.
 * @param byteCount populated for [MetricOperation.READ]/[MetricOperation.WRITE] (bytes
 * transferred on success), `null` otherwise.
 */
data class OperationMetric(
    val operation: MetricOperation,
    val peripheralUuid: String?,
    val success: Boolean,
    val durationMillis: Long,
    val attemptCount: Int,
    val byteCount: Int?
)

/**
 * A sink for [OperationMetric] events. Implement this to forward Blue Falcon operation metrics to
 * any telemetry backend (logs, a custom HTTP endpoint, Firebase/Crashlytics, OpenTelemetry via the
 * separate `blue-falcon-plugin-metrics-otel` module, etc.).
 *
 * `blue-falcon-plugin-metrics` depends only on `blue-falcon-core` and kotlinx-coroutines, so
 * implementing this interface never pulls in a specific telemetry SDK.
 */
interface MetricsExporter {
    /** Called once per completed operation, on the same coroutine that completed it. */
    fun record(metric: OperationMetric)
}

/**
 * Always-on, in-process aggregate view of every [OperationMetric] recorded so far, independent of
 * any configured [MetricsExporter]. Cheap to compute and update; suitable for e.g. a debug screen
 * showing live connection success rate.
 */
data class MetricsSnapshot(
    val connectSuccessCount: Long = 0,
    val connectFailureCount: Long = 0,
    val disconnectSuccessCount: Long = 0,
    val disconnectFailureCount: Long = 0,
    val scanSuccessCount: Long = 0,
    val scanFailureCount: Long = 0,
    val readSuccessCount: Long = 0,
    val readFailureCount: Long = 0,
    val writeSuccessCount: Long = 0,
    val writeFailureCount: Long = 0,
    val connectLatencyHistogram: Histogram = Histogram(),
    val readLatencyHistogram: Histogram = Histogram(),
    val writeLatencyHistogram: Histogram = Histogram(),
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    /**
     * Per-peripheral breakdown of the same counters above, keyed by [OperationMetric.peripheralUuid].
     * [MetricOperation.SCAN] is not peripheral-scoped and is never reflected here. Use
     * [metricsFor] to look up a specific peripheral with a zero-valued default.
     */
    val perPeripheral: Map<String, PeripheralMetrics> = emptyMap()
) {
    /** Returns the recorded metrics for [peripheralUuid], or a zero-valued [PeripheralMetrics] if none have been recorded yet. */
    fun metricsFor(peripheralUuid: String): PeripheralMetrics =
        perPeripheral[peripheralUuid] ?: PeripheralMetrics()
}

/**
 * Aggregate connect/disconnect/read/write counters and throughput scoped to a single peripheral,
 * identified by its [dev.bluefalcon.core.BluetoothPeripheral.uuid]. A per-peripheral counterpart
 * to the global counters on [MetricsSnapshot] - useful for showing connection health/throughput
 * for the specific device a user is currently viewing/connected to, rather than a fleet-wide
 * aggregate.
 */
data class PeripheralMetrics(
    val connectSuccessCount: Long = 0,
    val connectFailureCount: Long = 0,
    val disconnectSuccessCount: Long = 0,
    val disconnectFailureCount: Long = 0,
    val readSuccessCount: Long = 0,
    val readFailureCount: Long = 0,
    val writeSuccessCount: Long = 0,
    val writeFailureCount: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    /** Duration of the most recently completed connect attempt against this peripheral, successful or not. */
    val lastConnectLatencyMillis: Long? = null
)

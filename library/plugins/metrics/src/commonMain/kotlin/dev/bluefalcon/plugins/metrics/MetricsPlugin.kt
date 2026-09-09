package dev.bluefalcon.plugins.metrics

import dev.bluefalcon.core.plugin.BlueFalconClient
import dev.bluefalcon.core.plugin.BlueFalconOperationKind
import dev.bluefalcon.core.plugin.BlueFalconPlugin
import dev.bluefalcon.core.plugin.OperationTelemetry
import dev.bluefalcon.core.plugin.PluginConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Plugin that observes every scan/connect/disconnect/read/write operation via
 * [BlueFalconPlugin.onOperationCompleted] and records connection success/failure counts,
 * per-operation latency histograms, and read/write throughput.
 *
 * Built entirely on [dev.bluefalcon.core.plugin.PluginRegistry]'s existing instrumentation hook —
 * no engine changes are required, and installing this plugin never changes the behavior or
 * result of any BLE operation.
 *
 * Every recorded metric both updates the always-on in-process [snapshot] (cheap, allocation-light
 * aggregation with no external dependency) and is forwarded to every configured [MetricsExporter],
 * e.g. an OpenTelemetry-backed exporter from the separate `blue-falcon-plugin-metrics-otel`
 * module.
 *
 * Usage:
 * ```
 * val metrics = MetricsPlugin.create {
 *     exporters = listOf(myExporter)
 * }
 * val blueFalcon = BlueFalcon {
 *     install(metrics)
 * }
 * // Later, e.g. on a debug screen:
 * metrics.snapshot.value.connectSuccessCount
 * ```
 */
class MetricsPlugin(private val config: Config) : BlueFalconPlugin {

    /** Configuration for the metrics plugin. */
    class Config : PluginConfig() {
        /** Exporters that every recorded [OperationMetric] is forwarded to, in order. */
        var exporters: List<MetricsExporter> = emptyList()
    }

    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(MetricsSnapshot())

    /**
     * Always-on in-process aggregation of every operation recorded so far, independent of any
     * configured exporter.
     */
    val snapshot: StateFlow<MetricsSnapshot> = _snapshot.asStateFlow()

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // No client-side subscriptions are needed; all instrumentation flows through
        // onOperationCompleted, which PluginRegistry invokes directly.
    }

    override suspend fun onOperationCompleted(telemetry: OperationTelemetry) {
        val metric = OperationMetric(
            operation = telemetry.operation,
            peripheralUuid = telemetry.peripheralUuid,
            success = telemetry.success,
            durationMillis = telemetry.durationMillis,
            attemptCount = telemetry.attempts,
            byteCount = telemetry.byteCount
        )

        mutex.withLock {
            _snapshot.value = _snapshot.value.updatedWith(metric)
        }

        for (exporter in config.exporters) {
            exporter.record(metric)
        }
    }

    private fun MetricsSnapshot.updatedWith(metric: OperationMetric): MetricsSnapshot {
        // The three histograms are shared, mutable instances across every emitted snapshot value
        // (recording into them here mutates the same objects the previous snapshot value also
        // references) - see Histogram's KDoc. Only the scalar counters below are copied per-update.
        when (metric.operation) {
            BlueFalconOperationKind.CONNECT -> connectLatencyHistogram.record(metric.durationMillis)
            BlueFalconOperationKind.READ -> readLatencyHistogram.record(metric.durationMillis)
            BlueFalconOperationKind.WRITE -> writeLatencyHistogram.record(metric.durationMillis)
            BlueFalconOperationKind.DISCONNECT, BlueFalconOperationKind.SCAN -> Unit
        }

        return copy(
            connectSuccessCount = connectSuccessCount + if (metric.operation == BlueFalconOperationKind.CONNECT && metric.success) 1 else 0,
            connectFailureCount = connectFailureCount + if (metric.operation == BlueFalconOperationKind.CONNECT && !metric.success) 1 else 0,
            disconnectSuccessCount = disconnectSuccessCount + if (metric.operation == BlueFalconOperationKind.DISCONNECT && metric.success) 1 else 0,
            disconnectFailureCount = disconnectFailureCount + if (metric.operation == BlueFalconOperationKind.DISCONNECT && !metric.success) 1 else 0,
            scanSuccessCount = scanSuccessCount + if (metric.operation == BlueFalconOperationKind.SCAN && metric.success) 1 else 0,
            scanFailureCount = scanFailureCount + if (metric.operation == BlueFalconOperationKind.SCAN && !metric.success) 1 else 0,
            readSuccessCount = readSuccessCount + if (metric.operation == BlueFalconOperationKind.READ && metric.success) 1 else 0,
            readFailureCount = readFailureCount + if (metric.operation == BlueFalconOperationKind.READ && !metric.success) 1 else 0,
            writeSuccessCount = writeSuccessCount + if (metric.operation == BlueFalconOperationKind.WRITE && metric.success) 1 else 0,
            writeFailureCount = writeFailureCount + if (metric.operation == BlueFalconOperationKind.WRITE && !metric.success) 1 else 0,
            bytesRead = bytesRead + if (metric.operation == BlueFalconOperationKind.READ && metric.success) (metric.byteCount ?: 0) else 0,
            bytesWritten = bytesWritten + if (metric.operation == BlueFalconOperationKind.WRITE && metric.success) (metric.byteCount ?: 0) else 0,
            perPeripheral = metric.peripheralUuid?.let { uuid ->
                val existing = perPeripheral[uuid] ?: PeripheralMetrics()
                perPeripheral + (uuid to existing.updatedWith(metric))
            } ?: perPeripheral
        )
    }

    private fun PeripheralMetrics.updatedWith(metric: OperationMetric): PeripheralMetrics = copy(
        connectSuccessCount = connectSuccessCount + if (metric.operation == BlueFalconOperationKind.CONNECT && metric.success) 1 else 0,
        connectFailureCount = connectFailureCount + if (metric.operation == BlueFalconOperationKind.CONNECT && !metric.success) 1 else 0,
        disconnectSuccessCount = disconnectSuccessCount + if (metric.operation == BlueFalconOperationKind.DISCONNECT && metric.success) 1 else 0,
        disconnectFailureCount = disconnectFailureCount + if (metric.operation == BlueFalconOperationKind.DISCONNECT && !metric.success) 1 else 0,
        readSuccessCount = readSuccessCount + if (metric.operation == BlueFalconOperationKind.READ && metric.success) 1 else 0,
        readFailureCount = readFailureCount + if (metric.operation == BlueFalconOperationKind.READ && !metric.success) 1 else 0,
        writeSuccessCount = writeSuccessCount + if (metric.operation == BlueFalconOperationKind.WRITE && metric.success) 1 else 0,
        writeFailureCount = writeFailureCount + if (metric.operation == BlueFalconOperationKind.WRITE && !metric.success) 1 else 0,
        bytesRead = bytesRead + if (metric.operation == BlueFalconOperationKind.READ && metric.success) (metric.byteCount ?: 0) else 0,
        bytesWritten = bytesWritten + if (metric.operation == BlueFalconOperationKind.WRITE && metric.success) (metric.byteCount ?: 0) else 0,
        lastConnectLatencyMillis = if (metric.operation == BlueFalconOperationKind.CONNECT) metric.durationMillis else lastConnectLatencyMillis
    )

    companion object {
        /** Creates a new [MetricsPlugin] instance with the given configuration. */
        fun create(configure: Config.() -> Unit = {}): MetricsPlugin {
            val config = Config().apply(configure)
            return MetricsPlugin(config)
        }
    }
}

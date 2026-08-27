package dev.bluefalcon.plugins.proximity

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.plugin.BlueFalconClient
import dev.bluefalcon.core.plugin.BlueFalconPlugin
import dev.bluefalcon.core.plugin.PluginConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Plugin that provides smoothed RSSI readings, proximity zone classification, and rough
 * distance estimation for discovered BLE peripherals.
 *
 * Subscribes to `BlueFalcon.rssiUpdates` and applies a configurable smoothing filter per
 * peripheral to reduce the noise inherent in raw BLE RSSI samples. Exposes continuously-updated
 * [ProximityReading]s via [proximityReadings] and convenience lookups via [readingFor].
 *
 * **Distance estimation disclaimer:** BLE RSSI-to-distance conversion is inherently unreliable
 * beyond rough proximity classification. The [ProximityReading.estimatedDistanceMeters] value is
 * a rough estimate using the standard log-distance path-loss model, not a precise measurement.
 * Use [ProximityReading.zone] for most proximity-based UI and decision logic.
 *
 * Usage:
 * ```
 * val proximity = ProximityPlugin.create {
 *     smoothing = SmoothingStrategy.Kalman()
 *     immediateThreshold = -50f
 *     nearThreshold = -75f
 * }
 *
 * val blueFalcon = BlueFalcon {
 *     engine = myEngine
 *     install(proximity)
 * }
 *
 * // Observe all readings
 * blueFalcon.scope.launch {
 *     proximity.proximityReadings.collect { readings ->
 *         readings.forEach { (uuid, reading) ->
 *             println("$uuid: ${reading.zone}, ~${reading.estimatedDistanceMeters}m")
 *         }
 *     }
 * }
 *
 * // Or lookup a single peripheral
 * val reading = proximity.readingFor(peripheral)
 * ```
 */
class ProximityPlugin(private val config: Config) : BlueFalconPlugin {

    /**
     * Configuration for the proximity plugin.
     */
    class Config : PluginConfig() {
        /**
         * Smoothing strategy applied to each peripheral's raw RSSI samples independently.
         * Default: Kalman filter tuned for typical BLE RSSI characteristics.
         */
        var smoothing: SmoothingStrategy = SmoothingStrategy.Kalman()

        /**
         * Reference RSSI at 1 meter (TX power), used for distance estimation when a peripheral
         * doesn't advertise its own. This value varies by device/antenna; -59 dBm is a common
         * default for generic BLE beacons.
         */
        var defaultTxPower: Float = -59f

        /**
         * Path-loss exponent for distance estimation. Values:
         * - 2.0: Free space (ideal, unobstructed)
         * - 2.5-3.0: Typical indoor environment
         * - 3.0-4.0: Obstructed/cluttered environment
         */
        var pathLossExponent: Double = 2.0

        /**
         * Smoothed RSSI threshold (dBm) at or above which a peripheral is classified as [ProximityZone.Immediate].
         * Default -50 dBm corresponds roughly to devices within ~0.5m in a typical environment.
         */
        var immediateThreshold: Float = -50f

        /**
         * Smoothed RSSI threshold (dBm) at or above which a peripheral is classified as [ProximityZone.Near].
         * Peripherals below [immediateThreshold] but at or above this threshold are [ProximityZone.Near].
         * Default -75 dBm corresponds roughly to devices within ~3m in a typical environment.
         */
        var nearThreshold: Float = -75f

        /**
         * Minimum number of RSSI samples required before providing a distance estimate.
         * This prevents wild first-sample estimates when the filter hasn't yet converged.
         * Default 3 provides a reasonable warm-up period.
         */
        var minSamplesForDistance: Int = 3
    }

    private val _proximityReadings = MutableStateFlow<Map<String, ProximityReading>>(emptyMap())

    /**
     * Latest smoothed proximity reading per peripheral, keyed by peripheral UUID.
     *
     * This is a [StateFlow], so new collectors immediately receive the current value rather
     * than waiting for the next RSSI update. Readings are pruned when the corresponding
     * peripheral is removed from [BlueFalcon.peripherals].
     */
    val proximityReadings: StateFlow<Map<String, ProximityReading>> = _proximityReadings.asStateFlow()

    // Per-peripheral filter state: filter instance + sample count
    private val filterStates = mutableMapOf<String, FilterState>()

    private data class FilterState(
        val filter: RssiFilter,
        var sampleCount: Int = 0
    )

    /**
     * Get the current proximity reading for a specific peripheral, or null if no RSSI samples
     * have been received for it.
     */
    fun readingFor(peripheral: BluetoothPeripheral): ProximityReading? {
        return _proximityReadings.value[peripheral.uuid]
    }

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        // Cast to BlueFalcon to access rssiUpdates and peripherals flows
        val blueFalcon = client as? BlueFalcon ?: return

        // Subscribe to RSSI updates and process them through the smoothing filter
        blueFalcon.engine.scope.launch {
            blueFalcon.rssiUpdates.collect { (uuid, rawRssi) ->
                processRssiUpdate(uuid, rawRssi)
            }
        }

        // Subscribe to peripheral set changes to prune stale filter state and readings
        blueFalcon.engine.scope.launch {
            blueFalcon.peripherals.collect { currentPeripherals ->
                val currentUuids = currentPeripherals.mapTo(mutableSetOf()) { it.uuid }
                pruneStaleEntries(currentUuids)
            }
        }
    }

    private fun processRssiUpdate(uuid: String, rawRssi: Float) {
        // Get or create filter state for this peripheral
        val state = filterStates.getOrPut(uuid) {
            FilterState(filter = createFilter(config.smoothing))
        }

        state.sampleCount++
        val smoothedRssi = state.filter.filter(rawRssi)

        // Classify proximity zone based on smoothed RSSI
        val zone = classifyZone(smoothedRssi)

        // Estimate distance only if we have enough samples
        val estimatedDistance = if (state.sampleCount >= config.minSamplesForDistance) {
            estimateDistance(smoothedRssi, config.defaultTxPower, config.pathLossExponent)
        } else {
            null
        }

        val reading = ProximityReading(
            peripheralUuid = uuid,
            rawRssi = rawRssi,
            smoothedRssi = smoothedRssi,
            estimatedDistanceMeters = estimatedDistance,
            zone = zone,
            sampleCount = state.sampleCount
        )

        _proximityReadings.update { current ->
            current + (uuid to reading)
        }
    }

    private fun classifyZone(smoothedRssi: Float): ProximityZone {
        return when {
            smoothedRssi >= config.immediateThreshold -> ProximityZone.Immediate
            smoothedRssi >= config.nearThreshold -> ProximityZone.Near
            else -> ProximityZone.Far
        }
    }

    /**
     * Estimate distance using the standard log-distance path-loss model:
     * distance = 10 ^ ((txPower - rssi) / (10 * n))
     *
     * where:
     * - txPower is the expected RSSI at 1 meter (reference power)
     * - rssi is the measured/smoothed RSSI
     * - n is the path-loss exponent
     */
    private fun estimateDistance(smoothedRssi: Float, txPower: Float, pathLossExponent: Double): Double {
        val ratio = (txPower - smoothedRssi) / (10.0 * pathLossExponent)
        return 10.0.pow(ratio)
    }

    private fun pruneStaleEntries(currentUuids: Set<String>) {
        // Remove filter states for peripherals no longer present
        filterStates.keys.removeAll { it !in currentUuids }

        // Remove readings for peripherals no longer present
        _proximityReadings.update { current ->
            current.filterKeys { it in currentUuids }
        }
    }

    /**
     * Clear all proximity readings and reset filter state.
     * Useful when starting a fresh scan session.
     */
    fun clearAll() {
        filterStates.clear()
        _proximityReadings.value = emptyMap()
    }

    /**
     * Clear the proximity reading and filter state for a specific peripheral.
     */
    fun clearPeripheral(uuid: String) {
        filterStates.remove(uuid)
        _proximityReadings.update { current ->
            current - uuid
        }
    }

    companion object {
        /**
         * Creates a new ProximityPlugin instance with the given configuration.
         */
        fun create(configure: Config.() -> Unit = {}): ProximityPlugin {
            val config = Config().apply(configure)
            return ProximityPlugin(config)
        }
    }
}

/**
 * DSL function to create and configure a proximity plugin.
 */
fun proximityPlugin(configure: ProximityPlugin.Config.() -> Unit = {}): ProximityPlugin {
    return ProximityPlugin.create(configure)
}

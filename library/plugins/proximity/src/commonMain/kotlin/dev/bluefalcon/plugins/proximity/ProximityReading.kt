package dev.bluefalcon.plugins.proximity

/**
 * A processed proximity reading for a single peripheral.
 *
 * Contains both the raw and smoothed RSSI values, along with optional distance estimation
 * and proximity zone classification. Use [smoothedRssi] and [zone] for UI display and
 * proximity-based triggers; [rawRssi] is available for debugging or custom processing.
 *
 * @property peripheralUuid Platform-specific unique identifier for the peripheral
 * @property rawRssi The most recent unfiltered RSSI value in dBm
 * @property smoothedRssi The filtered/smoothed RSSI value in dBm
 * @property estimatedDistanceMeters Rough distance estimate in meters, or null if TX power
 *           is unknown or insufficient samples have been collected
 * @property zone Proximity classification based on smoothed RSSI thresholds
 * @property sampleCount Number of RSSI samples that have been processed for this peripheral
 */
data class ProximityReading(
    val peripheralUuid: String,
    val rawRssi: Float,
    val smoothedRssi: Float,
    val estimatedDistanceMeters: Double?,
    val zone: ProximityZone,
    val sampleCount: Int
)

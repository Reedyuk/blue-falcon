package dev.bluefalcon.plugins.proximity

/**
 * Proximity zone classification based on smoothed RSSI values.
 *
 * - [Immediate]: Very close range, typically within ~0.5 meters
 * - [Near]: Close range, typically within ~3 meters
 * - [Far]: Distant range, beyond Near threshold
 * - [Unknown]: Insufficient data to classify (e.g., no samples yet)
 */
enum class ProximityZone {
    Immediate,
    Near,
    Far,
    Unknown
}

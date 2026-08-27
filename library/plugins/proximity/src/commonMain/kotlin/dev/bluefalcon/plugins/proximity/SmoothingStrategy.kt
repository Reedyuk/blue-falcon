package dev.bluefalcon.plugins.proximity

/**
 * Smoothing strategy for filtering noisy BLE RSSI samples.
 *
 * BLE RSSI is notoriously noisy due to multipath interference, body shadowing, and antenna
 * orientation. These strategies reduce sample-to-sample variance to produce a more stable
 * proximity indicator.
 */
sealed class SmoothingStrategy {
    /**
     * Exponential Moving Average (EMA) filter.
     *
     * Computes: `smoothed = alpha * raw + (1 - alpha) * previous`
     *
     * A simple, low-overhead filter that's easy to reason about. The [alpha] parameter
     * controls responsiveness:
     * - Higher alpha (closer to 1.0) → more responsive to changes, more noise passes through
     * - Lower alpha (closer to 0.0) → slower response, smoother output
     *
     * @property alpha Smoothing factor in range (0, 1]. Default 0.2 provides good smoothing
     *           while remaining responsive to genuine proximity changes.
     */
    data class MovingAverage(val alpha: Double = 0.2) : SmoothingStrategy() {
        init {
            require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in range (0, 1]" }
        }
    }

    /**
     * 1D Kalman filter tuned for BLE RSSI signal characteristics.
     *
     * Provides better steady-state accuracy than EMA by explicitly modeling process and
     * measurement noise, at the cost of slightly more computation. This is the filter of
     * choice in many production BLE proximity implementations (e.g., Android's beacon APIs).
     *
     * @property processNoise Expected variance of the true RSSI between samples (how much
     *           the actual distance/environment changes). Lower values assume a more stable
     *           environment. Default 0.008 is suitable for a slowly-moving or stationary device.
     * @property measurementNoise Expected variance of the RSSI measurement itself (sensor noise).
     *           Higher values cause the filter to trust measurements less. Default 4.0 reflects
     *           typical BLE RSSI noise characteristics.
     */
    data class Kalman(
        val processNoise: Double = 0.008,
        val measurementNoise: Double = 4.0
    ) : SmoothingStrategy() {
        init {
            require(processNoise >= 0.0) { "processNoise must be non-negative" }
            require(measurementNoise > 0.0) { "measurementNoise must be positive" }
        }
    }

    /**
     * No smoothing applied; [ProximityReading.smoothedRssi] equals [ProximityReading.rawRssi].
     *
     * Useful for:
     * - Testing/debugging to see raw samples
     * - Comparison with smoothed strategies
     * - Applications that implement their own filtering downstream
     */
    data object None : SmoothingStrategy()
}

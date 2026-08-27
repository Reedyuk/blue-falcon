package dev.bluefalcon.plugins.proximity

/**
 * Internal filter interface for RSSI smoothing implementations.
 */
internal interface RssiFilter {
    /**
     * Process a new raw RSSI sample and return the smoothed value.
     */
    fun filter(rawRssi: Float): Float
    
    /**
     * Reset the filter state.
     */
    fun reset()
}

/**
 * Exponential Moving Average filter implementation.
 */
internal class MovingAverageFilter(private val alpha: Double) : RssiFilter {
    private var smoothedValue: Float? = null
    
    override fun filter(rawRssi: Float): Float {
        val previous = smoothedValue
        val smoothed = if (previous == null) {
            rawRssi
        } else {
            (alpha * rawRssi + (1 - alpha) * previous).toFloat()
        }
        smoothedValue = smoothed
        return smoothed
    }
    
    override fun reset() {
        smoothedValue = null
    }
}

/**
 * 1D Kalman filter implementation tuned for BLE RSSI characteristics.
 *
 * This is a simplified 1D Kalman filter where:
 * - State: estimated true RSSI
 * - Measurement: raw RSSI sample
 * - Process model: RSSI evolves with noise (random walk)
 * - Measurement model: measured RSSI = true RSSI + noise
 */
internal class KalmanFilter(
    private val processNoise: Double,
    private val measurementNoise: Double
) : RssiFilter {
    
    // State estimate
    private var estimate: Double? = null
    
    // Error covariance (uncertainty in the estimate)
    private var errorCovariance: Double = 1.0
    
    override fun filter(rawRssi: Float): Float {
        val measurement = rawRssi.toDouble()
        
        val currentEstimate = estimate
        if (currentEstimate == null) {
            // First sample: initialize state to the measurement
            estimate = measurement
            errorCovariance = measurementNoise
            return rawRssi
        }
        
        // Prediction step
        // State prediction: x_pred = x_est (assuming no movement model, random walk)
        val predictedEstimate = currentEstimate
        // Error covariance prediction: P_pred = P + Q (process noise increases uncertainty)
        val predictedCovariance = errorCovariance + processNoise
        
        // Update step
        // Kalman gain: K = P_pred / (P_pred + R)
        val kalmanGain = predictedCovariance / (predictedCovariance + measurementNoise)
        
        // State update: x_est = x_pred + K * (z - x_pred)
        val innovation = measurement - predictedEstimate
        val newEstimate = predictedEstimate + kalmanGain * innovation
        
        // Error covariance update: P = (1 - K) * P_pred
        val newCovariance = (1 - kalmanGain) * predictedCovariance
        
        estimate = newEstimate
        errorCovariance = newCovariance
        
        return newEstimate.toFloat()
    }
    
    override fun reset() {
        estimate = null
        errorCovariance = 1.0
    }
}

/**
 * Pass-through "filter" that returns the raw value unchanged.
 */
internal class NoOpFilter : RssiFilter {
    override fun filter(rawRssi: Float): Float = rawRssi
    override fun reset() {}
}

/**
 * Factory function to create a filter from a smoothing strategy.
 */
internal fun createFilter(strategy: SmoothingStrategy): RssiFilter = when (strategy) {
    is SmoothingStrategy.MovingAverage -> MovingAverageFilter(strategy.alpha)
    is SmoothingStrategy.Kalman -> KalmanFilter(strategy.processNoise, strategy.measurementNoise)
    is SmoothingStrategy.None -> NoOpFilter()
}

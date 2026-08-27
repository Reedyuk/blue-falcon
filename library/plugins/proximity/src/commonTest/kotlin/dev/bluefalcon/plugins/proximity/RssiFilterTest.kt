package dev.bluefalcon.plugins.proximity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the RSSI smoothing filter implementations.
 */
class RssiFilterTest {

    @Test
    fun `moving average filter returns raw value on first sample`() {
        val filter = MovingAverageFilter(alpha = 0.2)
        
        val result = filter.filter(-60f)
        
        assertEquals(-60f, result, 0.001f)
    }

    @Test
    fun `moving average filter smooths subsequent samples`() {
        val filter = MovingAverageFilter(alpha = 0.2)
        
        // First sample initializes
        filter.filter(-60f)
        
        // Second sample: 0.2 * (-70) + 0.8 * (-60) = -14 + -48 = -62
        val result = filter.filter(-70f)
        
        assertEquals(-62f, result, 0.001f)
    }

    @Test
    fun `moving average filter converges toward raw value over time`() {
        val filter = MovingAverageFilter(alpha = 0.5)
        
        // Start at -60, then consistently receive -80
        filter.filter(-60f)
        
        // After many samples at -80, should converge close to -80
        repeat(20) { filter.filter(-80f) }
        
        val result = filter.filter(-80f)
        assertTrue(result < -79f && result > -81f, "Expected ~-80 but got $result")
    }

    @Test
    fun `moving average filter with alpha 1 returns raw value`() {
        val filter = MovingAverageFilter(alpha = 1.0)
        
        filter.filter(-60f)
        val result = filter.filter(-70f)
        
        // alpha=1 means no smoothing: smoothed = 1.0 * raw + 0.0 * previous
        assertEquals(-70f, result, 0.001f)
    }

    @Test
    fun `moving average filter reset clears state`() {
        val filter = MovingAverageFilter(alpha = 0.2)
        
        filter.filter(-60f)
        filter.filter(-70f)
        filter.reset()
        
        // After reset, first sample should return raw value
        val result = filter.filter(-80f)
        assertEquals(-80f, result, 0.001f)
    }

    @Test
    fun `kalman filter returns raw value on first sample`() {
        val filter = KalmanFilter(processNoise = 0.008, measurementNoise = 4.0)
        
        val result = filter.filter(-60f)
        
        assertEquals(-60f, result, 0.001f)
    }

    @Test
    fun `kalman filter smooths noisy samples`() {
        val filter = KalmanFilter(processNoise = 0.008, measurementNoise = 4.0)
        
        // Initialize
        filter.filter(-60f)
        
        // A large jump in raw value should be partially smoothed
        val result = filter.filter(-80f)
        
        // Kalman should produce a value between -60 and -80
        assertTrue(result < -60f && result > -80f, "Expected smoothed value but got $result")
    }

    @Test
    fun `kalman filter converges toward stable signal`() {
        val filter = KalmanFilter(processNoise = 0.008, measurementNoise = 4.0)
        
        // Initialize at -60
        filter.filter(-60f)
        
        // Feed consistent -70 samples
        repeat(50) { filter.filter(-70f) }
        
        val result = filter.filter(-70f)
        
        // Should converge close to -70
        assertTrue(result < -69f && result > -71f, "Expected ~-70 but got $result")
    }

    @Test
    fun `kalman filter reset clears state`() {
        val filter = KalmanFilter(processNoise = 0.008, measurementNoise = 4.0)
        
        filter.filter(-60f)
        filter.filter(-70f)
        filter.reset()
        
        // After reset, first sample should return raw value
        val result = filter.filter(-80f)
        assertEquals(-80f, result, 0.001f)
    }

    @Test
    fun `kalman filter with high measurement noise trusts measurements less`() {
        val highNoise = KalmanFilter(processNoise = 0.008, measurementNoise = 20.0)
        val lowNoise = KalmanFilter(processNoise = 0.008, measurementNoise = 1.0)
        
        highNoise.filter(-60f)
        lowNoise.filter(-60f)
        
        val highNoiseResult = highNoise.filter(-80f)
        val lowNoiseResult = lowNoise.filter(-80f)
        
        // High measurement noise should smooth more (stay closer to previous)
        assertTrue(highNoiseResult > lowNoiseResult, 
            "High noise filter should trust measurement less: $highNoiseResult vs $lowNoiseResult")
    }

    @Test
    fun `noop filter returns raw value unchanged`() {
        val filter = NoOpFilter()
        
        assertEquals(-60f, filter.filter(-60f))
        assertEquals(-70f, filter.filter(-70f))
        assertEquals(-80f, filter.filter(-80f))
    }

    @Test
    fun `createFilter returns correct filter type for each strategy`() {
        val maFilter = createFilter(SmoothingStrategy.MovingAverage(0.3))
        val kalmanFilter = createFilter(SmoothingStrategy.Kalman())
        val noOpFilter = createFilter(SmoothingStrategy.None)
        
        assertTrue(maFilter is MovingAverageFilter)
        assertTrue(kalmanFilter is KalmanFilter)
        assertTrue(noOpFilter is NoOpFilter)
    }
}

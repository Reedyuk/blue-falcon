package dev.bluefalcon.plugins.proximity

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for distance estimation using the log-distance path-loss model.
 */
class DistanceEstimationTest {

    @Test
    fun `distance is 1 meter when rssi equals tx power`() {
        val distance = estimateDistance(
            smoothedRssi = -59f,
            txPower = -59f,
            pathLossExponent = 2.0
        )
        
        assertEquals(1.0, distance, 0.001)
    }

    @Test
    fun `distance increases as rssi decreases`() {
        val txPower = -59f
        val pathLoss = 2.0
        
        val close = estimateDistance(-50f, txPower, pathLoss)
        val medium = estimateDistance(-70f, txPower, pathLoss)
        val far = estimateDistance(-90f, txPower, pathLoss)
        
        assertTrue(close < medium, "Expected $close < $medium")
        assertTrue(medium < far, "Expected $medium < $far")
    }

    @Test
    fun `distance matches expected formula for known values`() {
        // d = 10 ^ ((txPower - rssi) / (10 * n))
        // With txPower = -59, rssi = -79, n = 2:
        // d = 10 ^ ((-59 - -79) / (10 * 2)) = 10 ^ (20/20) = 10 ^ 1 = 10m
        val distance = estimateDistance(-79f, -59f, 2.0)
        
        assertEquals(10.0, distance, 0.001)
    }

    @Test
    fun `higher path loss exponent yields shorter distance estimate`() {
        val rssi = -79f
        val txPower = -59f
        
        // In free space (n=2), the estimate is larger
        val freeSpace = estimateDistance(rssi, txPower, 2.0)
        // In obstructed environment (n=4), same RSSI implies closer actual distance
        val obstructed = estimateDistance(rssi, txPower, 4.0)
        
        assertTrue(freeSpace > obstructed, 
            "Free space ($freeSpace) should estimate larger distance than obstructed ($obstructed)")
    }

    @Test
    fun `distance is less than 1 meter when rssi stronger than tx power`() {
        // Very strong signal indicates sub-1m distance
        val distance = estimateDistance(-40f, -59f, 2.0)
        
        assertTrue(distance < 1.0, "Expected distance < 1m but got $distance")
    }

    @Test
    fun `typical beacon ranges produce reasonable estimates`() {
        val txPower = -59f
        val pathLoss = 2.5 // typical indoor
        
        // Immediate range (strong signal)
        val immediate = estimateDistance(-45f, txPower, pathLoss)
        assertTrue(immediate < 1.0, "Immediate should be < 1m: $immediate")
        
        // Near range
        val near = estimateDistance(-70f, txPower, pathLoss)
        assertTrue(near > 1.0 && near < 10.0, "Near should be 1-10m: $near")
        
        // Far range
        val far = estimateDistance(-85f, txPower, pathLoss)
        assertTrue(far > 5.0, "Far should be > 5m: $far")
    }

    /**
     * Distance estimation formula matching the plugin implementation.
     */
    private fun estimateDistance(
        smoothedRssi: Float,
        txPower: Float,
        pathLossExponent: Double
    ): Double {
        val ratio = (txPower - smoothedRssi) / (10.0 * pathLossExponent)
        return 10.0.pow(ratio)
    }
}

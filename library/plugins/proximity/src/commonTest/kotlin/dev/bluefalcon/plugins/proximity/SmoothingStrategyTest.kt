package dev.bluefalcon.plugins.proximity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SmoothingStrategy] configuration validation.
 */
class SmoothingStrategyTest {

    @Test
    fun `MovingAverage with default alpha is valid`() {
        val strategy = SmoothingStrategy.MovingAverage()
        assertEquals(0.2, strategy.alpha)
    }

    @Test
    fun `MovingAverage with custom alpha is valid`() {
        val strategy = SmoothingStrategy.MovingAverage(alpha = 0.5)
        assertEquals(0.5, strategy.alpha)
    }

    @Test
    fun `MovingAverage alpha can be 1`() {
        val strategy = SmoothingStrategy.MovingAverage(alpha = 1.0)
        assertEquals(1.0, strategy.alpha)
    }

    @Test
    fun `Kalman with default parameters is valid`() {
        val strategy = SmoothingStrategy.Kalman()
        assertEquals(0.008, strategy.processNoise)
        assertEquals(4.0, strategy.measurementNoise)
    }

    @Test
    fun `Kalman with custom parameters is valid`() {
        val strategy = SmoothingStrategy.Kalman(
            processNoise = 0.01,
            measurementNoise = 2.0
        )
        assertEquals(0.01, strategy.processNoise)
        assertEquals(2.0, strategy.measurementNoise)
    }

    @Test
    fun `Kalman processNoise can be zero`() {
        val strategy = SmoothingStrategy.Kalman(processNoise = 0.0)
        assertEquals(0.0, strategy.processNoise)
    }

    @Test
    fun `None strategy is singleton`() {
        val none1 = SmoothingStrategy.None
        val none2 = SmoothingStrategy.None
        assertTrue(none1 === none2)
    }
}

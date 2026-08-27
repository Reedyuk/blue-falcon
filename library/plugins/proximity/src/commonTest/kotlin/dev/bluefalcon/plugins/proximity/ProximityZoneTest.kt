package dev.bluefalcon.plugins.proximity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for proximity zone classification logic.
 */
class ProximityZoneTest {

    @Test
    fun `immediate zone for strong signal`() {
        assertEquals(ProximityZone.Immediate, classifyZone(-40f, -50f, -75f))
        assertEquals(ProximityZone.Immediate, classifyZone(-50f, -50f, -75f))
    }

    @Test
    fun `near zone for medium signal`() {
        assertEquals(ProximityZone.Near, classifyZone(-60f, -50f, -75f))
        assertEquals(ProximityZone.Near, classifyZone(-75f, -50f, -75f))
    }

    @Test
    fun `far zone for weak signal`() {
        assertEquals(ProximityZone.Far, classifyZone(-80f, -50f, -75f))
        assertEquals(ProximityZone.Far, classifyZone(-100f, -50f, -75f))
    }

    @Test
    fun `custom thresholds are respected`() {
        // More lenient thresholds
        assertEquals(ProximityZone.Immediate, classifyZone(-60f, -60f, -85f))
        assertEquals(ProximityZone.Near, classifyZone(-70f, -60f, -85f))
        assertEquals(ProximityZone.Far, classifyZone(-90f, -60f, -85f))
    }

    /**
     * Helper function mimicking the plugin's zone classification logic.
     */
    private fun classifyZone(
        smoothedRssi: Float,
        immediateThreshold: Float,
        nearThreshold: Float
    ): ProximityZone {
        return when {
            smoothedRssi >= immediateThreshold -> ProximityZone.Immediate
            smoothedRssi >= nearThreshold -> ProximityZone.Near
            else -> ProximityZone.Far
        }
    }
}

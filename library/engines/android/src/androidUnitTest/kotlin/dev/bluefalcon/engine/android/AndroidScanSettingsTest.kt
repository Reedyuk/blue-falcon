package dev.bluefalcon.engine.android

import android.bluetooth.le.ScanSettings
import dev.bluefalcon.core.ScanConfig
import dev.bluefalcon.core.ScanMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AndroidScanSettingsTest {
    @Test
    fun defaultConfigKeepsLowLatencyWithoutBatching() {
        assertEquals(
            AndroidScanSettings(ScanSettings.SCAN_MODE_LOW_LATENCY, reportDelayMillis = 0),
            ScanConfig().toAndroidScanSettings(),
        )
    }

    @Test
    fun everyModeAndReportDelayMapsToFrameworkValues() {
        val expected = mapOf(
            ScanMode.Opportunistic to ScanSettings.SCAN_MODE_OPPORTUNISTIC,
            ScanMode.LowPower to ScanSettings.SCAN_MODE_LOW_POWER,
            ScanMode.Balanced to ScanSettings.SCAN_MODE_BALANCED,
            ScanMode.LowLatency to ScanSettings.SCAN_MODE_LOW_LATENCY,
        )
        expected.forEach { (mode, androidMode) ->
            assertEquals(androidMode, ScanConfig(mode = mode).toAndroidScanSettings().scanMode)
        }
        assertEquals(2_000L, ScanConfig(reportDelay = 2.seconds).toAndroidScanSettings().reportDelayMillis)
    }
}

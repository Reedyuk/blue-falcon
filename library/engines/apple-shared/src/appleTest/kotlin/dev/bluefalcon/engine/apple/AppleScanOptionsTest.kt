package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.ScanConfig
import dev.bluefalcon.core.ScanMode
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class AppleScanOptionsTest {
    @Test
    fun defaultConfigKeepsAllowDuplicates() {
        assertEquals(
            mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
            ScanConfig().toAppleScanOptions(),
        )
    }

    @Test
    fun allowDuplicatesIsAppliedAndAndroidOnlyFieldsAreIgnored() {
        val options = ScanConfig(
            mode = ScanMode.LowPower,
            reportDelay = 5.seconds,
            allowDuplicates = false,
        ).toAppleScanOptions()

        assertEquals(mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to false), options)
    }
}

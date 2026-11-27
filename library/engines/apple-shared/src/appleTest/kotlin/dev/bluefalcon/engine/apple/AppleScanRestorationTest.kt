package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.toUuid
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanOptionsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanServicesKey
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCentralManagerScanOptionSolicitedServiceUUIDsKey
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.Foundation.numberWithBool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #325, option 1: Core Bluetooth restores the scan of the engine's scanning manager only. */
class AppleScanRestorationTest {
    private val heartRate = ServiceFilter("180D".toUuid())
    private val custom = ServiceFilter("6E400001-B5A3-F393-E0A9-E50E24DCCA9E".toUuid())

    @Test fun restoredScanServicesAndOptionsAreParsed() {
        val state = parseCentralRestoredState(
            mapOf<Any?, Any?>(
                CBCentralManagerRestoredStateScanServicesKey to listOf(
                    CBUUID.UUIDWithString("180D"),
                    CBUUID.UUIDWithString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                ),
                CBCentralManagerRestoredStateScanOptionsKey to mapOf<Any?, Any?>(
                    CBCentralManagerScanOptionAllowDuplicatesKey to NSNumber.numberWithBool(true),
                    CBCentralManagerScanOptionSolicitedServiceUUIDsKey to listOf(CBUUID.UUIDWithString("180D")),
                ),
            ),
        )

        assertEquals(AppleRestoredScan(listOf(heartRate, custom), allowDuplicates = true, listOf(heartRate)), state.scan)
        assertEquals(0, state.ignoredPeripheralCount)
    }

    @Test fun scanOptionsWithoutServicesMeanAllServices() {
        val state = parseCentralRestoredState(
            mapOf<Any?, Any?>(CBCentralManagerRestoredStateScanOptionsKey to emptyMap<Any?, Any?>()),
        )

        assertEquals(AppleRestoredScan(emptyList(), allowDuplicates = false, emptyList()), state.scan)
    }

    @Test fun stateWithoutScanKeysHasNoRestoredScan() {
        val state = parseCentralRestoredState(
            mapOf<Any?, Any?>(CBCentralManagerRestoredStatePeripheralsKey to listOf(NSObject(), NSObject())),
        )

        assertNull(state.scan)
        assertEquals(2, state.ignoredPeripheralCount)
    }

    @Test fun restoredScanIsPublishedAndTheEngineReportsScanning() {
        val restoration = AppleScanRestoration(logger = null)
        val scan = AppleRestoredScan(listOf(heartRate), allowDuplicates = true, emptyList())
        var scanningWhenReported: Boolean? = null
        var publishedBeforeScanning = false

        assertTrue(restoration.adopt(AppleCentralRestoredState(scan, ignoredPeripheralCount = 0)) {
            publishedBeforeScanning = restoration.restoredScan.value != null
            scanningWhenReported = true
        })
        assertEquals(true, scanningWhenReported)
        assertFalse(publishedBeforeScanning, "isScanning is reported before the scan is published")
        assertEquals(scan, restoration.restoredScan.value)
    }

    @Test fun restorationIsAdoptedOnlyOnce() {
        val restoration = AppleScanRestoration(logger = null)
        val first = AppleRestoredScan(listOf(heartRate), allowDuplicates = false, emptyList())
        val second = AppleRestoredScan(listOf(custom), allowDuplicates = false, emptyList())

        assertTrue(restoration.adopt(AppleCentralRestoredState(first, 0)))
        assertFalse(restoration.adopt(AppleCentralRestoredState(second, 0)))
        assertEquals(first, restoration.restoredScan.value)
    }

    @Test fun appScanChoiceMadeBeforeRestorationIsKept() {
        val restoration = AppleScanRestoration(logger = null)
        restoration.appChangedScan()
        val scan = AppleRestoredScan(listOf(heartRate), allowDuplicates = false, emptyList())

        assertFalse(restoration.adopt(AppleCentralRestoredState(scan, 0)), "stopScanning before restoration wins")
        assertEquals(scan, restoration.restoredScan.value, "the app still learns what was restored")
    }

    @Test fun restoredPeripheralsAreReportedAsNotRestored() {
        val warnings = mutableListOf<String>()
        val restoration = AppleScanRestoration(RecordingLogger(warnings))

        assertFalse(restoration.adopt(AppleCentralRestoredState(scan = null, ignoredPeripheralCount = 3)))
        assertNull(restoration.restoredScan.value)
        assertEquals(1, warnings.size)
        assertTrue("connect to them again" in warnings.single())
    }

    @Test fun missingStateIsIgnored() {
        val restoration = AppleScanRestoration(logger = null)
        assertFalse(restoration.adopt(null))
        assertNull(restoration.restoredScan.value)
    }

    @Test fun configurationDefaultsToNoRestorationAndRejectsBlankIdentifiers() {
        assertNull(AppleEngineConfiguration().restorationIdentifier)
        assertEquals("mesh.central", AppleEngineConfiguration("mesh.central").restorationIdentifier)
        assertFailsWith<IllegalArgumentException> { AppleEngineConfiguration(" ") }
    }

    private class RecordingLogger(private val warnings: MutableList<String>) : Logger {
        override fun error(message: String, cause: Throwable?) = Unit
        override fun warn(message: String, cause: Throwable?) { warnings += message }
        override fun info(message: String, cause: Throwable?) = Unit
        override fun debug(message: String, cause: Throwable?) = Unit
    }
}

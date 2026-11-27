package dev.bluefalcon.engine.ios

import dev.bluefalcon.core.ServiceFilter
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.engine.apple.AppleCentralRestoredState
import dev.bluefalcon.engine.apple.AppleEngineConfiguration
import dev.bluefalcon.engine.apple.AppleRestoredScan
import dev.bluefalcon.engine.apple.BluetoothPeripheralManager
import dev.bluefalcon.engine.apple.CBCentralManagerCallback
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanServicesKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Issue #325. The test bundle has no `bluetooth-central` background mode, so no identifier is set here. */
class IosEngineScanRestorationTest {
    @Test fun engineWithConfigurationStartsWithoutARestoredScan() {
        val engine = IosEngine(AppleEngineConfiguration(restorationIdentifier = null))

        assertNull(engine.restoredScan.value)
        assertFalse(engine.isScanning)
        assertFalse(engine.centralCapabilities.restoration, "connections are not restored")
    }

    @Test fun willRestoreStateIsHandedToTheOwnerParsed() {
        val received = mutableListOf<AppleCentralRestoredState>()
        val manager = BluetoothPeripheralManager(NoCallbacks, null) { received += it }

        manager.centralManager(
            manager.centralManager,
            willRestoreState = mapOf<Any?, Any?>(
                CBCentralManagerRestoredStateScanServicesKey to listOf(CBUUID.UUIDWithString("180D")),
            ),
        )

        assertEquals(
            AppleRestoredScan(listOf(ServiceFilter("180D".toUuid())), allowDuplicates = false, emptyList()),
            received.single().scan,
        )
        manager.close(null)
    }

    private object NoCallbacks : CBCentralManagerCallback {
        override fun onStateUpdated(state: CBManagerState) = Unit
        override fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber) = Unit
        override fun onPeripheralConnected(peripheral: CBPeripheral) = Unit
        override fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) = Unit
        override fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?) = Unit
    }
}

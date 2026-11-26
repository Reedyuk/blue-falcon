package dev.bluefalcon.engine.apple

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create

/**
 * Callback interface for CBCentralManager delegate events
 */
interface CBCentralManagerCallback {
    fun onStateUpdated(state: CBManagerState)
    fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: NSNumber)
    fun onPeripheralConnected(peripheral: CBPeripheral)
    fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?)
    fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?)
}

/**
 * Wrapper for CBCentralManager that handles delegate callbacks
 *
 * @param restorationIdentifier passed as `CBCentralManagerOptionRestoreIdentifierKey`. Only the
 * engine's scanning manager uses it; per-peer connection managers never do.
 */
class BluetoothPeripheralManager internal constructor(
    private val callback: CBCentralManagerCallback,
    restorationIdentifier: String?,
    private val onRestoredState: (AppleCentralRestoredState) -> Unit,
) : NSObject(), CBCentralManagerDelegateProtocol {

    constructor(callback: CBCentralManagerCallback) : this(callback, null, {})

    private val owner = AppleNativeManagerOwner()
    private val _managerState = MutableStateFlow<CBManagerState>(CBManagerStateUnknown)
    val managerState: StateFlow<CBManagerState> = _managerState

    // Passing a null delegate queue makes CoreBluetooth deliver every central-role
    // callback (scan discovery, connect/disconnect, service/characteristic
    // discovery, notifications) on the main dispatch queue. With several mesh
    // peers connected and discovering/notifying concurrently, this floods the main
    // queue and makes the UI (which also runs on it) sluggish/unresponsive. Use a
    // dedicated serial queue instead, matching the peripheral-role stack
    // (FrameworkApplePeripheralStack), which already does this correctly.
    private val delegateQueue = dispatch_queue_create("dev.bluefalcon.engine.apple.central", null)

    private var nativeManager: CBCentralManager? = CBCentralManager(
        this,
        delegateQueue,
        restorationIdentifier?.let { mapOf<Any?, Any?>(CBCentralManagerOptionRestoreIdentifierKey to it) },
    )
    val centralManager: CBCentralManager
        get() = checkNotNull(nativeManager) { "Apple native manager is closed" }
    
    suspend fun awaitPoweredOn() {
        withTimeout(10_000L) { owner.awaitReady(managerState) { it == CBManagerStatePoweredOn } }
    }

    fun close(peripheral: CBPeripheral?) {
        val manager = nativeManager
        owner.close(buildList {
            if (manager != null) {
                add { manager.delegate = null }
                if (peripheral != null) {
                    add { peripheral.delegate = null }
                    add { manager.cancelPeripheralConnection(peripheral) }
                }
                add { manager.stopScan() }
            }
            add { nativeManager = null }
        })
    }

    /**
     * Called only for a manager created with a restoration identifier, before the first state
     * update. It can run while the owner is still constructing this manager, so [onRestoredState]
     * must only touch state that exists before the manager.
     */
    override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) = owner.forward {
        onRestoredState(parseCentralRestoredState(willRestoreState))
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) = owner.forward {
        _managerState.value = central.state
        callback.onStateUpdated(central.state)
    }
    
    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber
    ) {
        owner.forward { callback.onPeripheralDiscovered(didDiscoverPeripheral, advertisementData, RSSI) }
    }
    
    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        owner.forward { callback.onPeripheralConnected(didConnectPeripheral) }
    }
    
    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        owner.forward { callback.onPeripheralDisconnected(didDisconnectPeripheral, error) }
    }
    
    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        owner.forward { callback.onPeripheralConnectionFailed(didFailToConnectPeripheral, error) }
    }
}

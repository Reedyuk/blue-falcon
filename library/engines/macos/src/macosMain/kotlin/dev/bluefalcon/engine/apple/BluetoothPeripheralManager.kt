package dev.bluefalcon.engine.apple

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

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
 */
class BluetoothPeripheralManager(
    private val callback: CBCentralManagerCallback
) : NSObject(), CBCentralManagerDelegateProtocol {
    
    private val _managerState = MutableStateFlow<CBManagerState>(CBManagerStateUnknown)
    val managerState: StateFlow<CBManagerState> = _managerState
    
    val centralManager: CBCentralManager = CBCentralManager(this, null)
    
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        _managerState.value = central.state
        callback.onStateUpdated(central.state)
    }
    
    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber
    ) {
        callback.onPeripheralDiscovered(didDiscoverPeripheral, advertisementData, RSSI)
    }
    
    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        callback.onPeripheralConnected(didConnectPeripheral)
    }
    
    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        callback.onPeripheralDisconnected(didDisconnectPeripheral, error)
    }
    
    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        callback.onPeripheralConnectionFailed(didFailToConnectPeripheral, error)
    }
}

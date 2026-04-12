package dev.bluefalcon.engine.ios

import dev.bluefalcon.core.*
import dev.bluefalcon.engine.apple.AppleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * iOS-specific implementation of BlueFalconEngine.
 * This is a thin wrapper around the shared AppleEngine implementation.
 */
class IosEngine : BlueFalconEngine {
    
    private val appleEngine = AppleEngine()
    
    override val scope: CoroutineScope
        get() = appleEngine.scope
    
    override val peripherals: StateFlow<Set<BluetoothPeripheral>>
        get() = appleEngine.peripherals
    
    override val managerState: StateFlow<BluetoothManagerState>
        get() = appleEngine.managerState
    
    override val isScanning: Boolean
        get() = appleEngine.isScanning
    
    override suspend fun scan(filters: List<ServiceFilter>) =
        appleEngine.scan(filters)
    
    override suspend fun stopScanning() =
        appleEngine.stopScanning()
    
    override fun clearPeripherals() =
        appleEngine.clearPeripherals()
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) =
        appleEngine.connect(peripheral, autoConnect)
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) =
        appleEngine.disconnect(peripheral)
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState =
        appleEngine.connectionState(peripheral)
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? =
        appleEngine.retrievePeripheral(identifier)
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) =
        appleEngine.requestConnectionPriority(peripheral, priority)
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) =
        appleEngine.discoverServices(peripheral, serviceUUIDs)
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) = appleEngine.discoverCharacteristics(peripheral, service, characteristicUUIDs)
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) = appleEngine.readCharacteristic(peripheral, characteristic)
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) = appleEngine.writeCharacteristic(peripheral, characteristic, value, writeType)
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) = appleEngine.writeCharacteristic(peripheral, characteristic, value, writeType)
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) = appleEngine.notifyCharacteristic(peripheral, characteristic, notify)
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) = appleEngine.indicateCharacteristic(peripheral, characteristic, indicate)
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) = appleEngine.readDescriptor(peripheral, characteristic, descriptor)
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) = appleEngine.writeDescriptor(peripheral, descriptor, value)
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) =
        appleEngine.changeMTU(peripheral, mtuSize)
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean =
        appleEngine.refreshGattCache(peripheral)
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) =
        appleEngine.openL2capChannel(peripheral, psm)
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) =
        appleEngine.createBond(peripheral)
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) =
        appleEngine.removeBond(peripheral)
}

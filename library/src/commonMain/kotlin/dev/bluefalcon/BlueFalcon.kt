package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

expect class BlueFalcon(
    log: Logger? = PrintLnLogger,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean = true
) {

    val scope: CoroutineScope

    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean

    val managerState: StateFlow<BluetoothManagerState>

    internal val _peripherals: MutableStateFlow<Set<BluetoothPeripheral>>
    val peripherals: NativeFlow<Set<BluetoothPeripheral>>

    fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    fun scan(filters: ServiceFilter? = null)

    fun stopScanning()

    fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())

    fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    )

    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    )

    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    )

    fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    )

    fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    )

    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    )

    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )

    fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )

    fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    )

    fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    )

    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)

}

enum class BluetoothManagerState {
    Ready, NotReady
}

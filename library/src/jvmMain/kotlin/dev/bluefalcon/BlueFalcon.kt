package dev.bluefalcon

import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import java.lang.Exception
//import javax.bluetooth.*

actual class BlueFalcon actual constructor(context: ApplicationContext, serviceUUID: String?) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    private val bluetoothManager = BluetoothCentralManager(BluetoothCentralManagerCallback())

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
    }

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    actual fun scan() {
        bluetoothManager.scanForPeripherals()
//        try {
//            val localDevice = LocalDevice.getLocalDevice()
//            val agent = localDevice.discoveryAgent
//            agent.startInquiry(DiscoveryAgent.GIAC, BluetoothDiscoveryListener())
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }

    actual fun stopScanning() {
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
    }

    class BluetoothCentralManagerCallback: com.welie.blessed.BluetoothCentralManagerCallback() {

    }

//    class BluetoothDiscoveryListener: DiscoveryListener {
//        override fun deviceDiscovered(btDevice: RemoteDevice?, cod: DeviceClass?) {
//            log(btDevice?.getFriendlyName(false) ?: "deviceDiscovered")
//        }
//
//        override fun servicesDiscovered(transID: Int, servRecord: Array<out ServiceRecord>?) {
//        }
//
//        override fun serviceSearchCompleted(transID: Int, respCode: Int) {
//        }
//
//        override fun inquiryCompleted(discType: Int) {
//        }
//
//    }

}
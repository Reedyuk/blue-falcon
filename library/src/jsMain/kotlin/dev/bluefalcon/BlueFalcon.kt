package dev.bluefalcon

import kotlinx.browser.window
import org.w3c.dom.Navigator

@JsName("blueFalcon")
val blueFalcon = BlueFalcon(ApplicationContext(), null)

actual class BlueFalcon actual constructor(context: ApplicationContext, serviceUUID: String?) {

    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    private inline val Navigator.bluetooth: Bluetooth get() = asDynamic().bluetooth as Bluetooth

    @JsName("addDelegate")
    fun addDelegate(blueFalconDelegate: BlueFalconDelegate) { delegates.add(blueFalconDelegate) }

    @JsName("removeDelegate")
    fun removeDelegate(blueFalconDelegate: BlueFalconDelegate) { delegates.remove(blueFalconDelegate) }

    @JsName("connect")
    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log("connect -> ${bluetoothPeripheral.device}:${bluetoothPeripheral.device.gatt} gatt connected? ${bluetoothPeripheral.device.gatt?.connected}")
        if(bluetoothPeripheral.device.gatt?.connected == true) {
            delegates.forEach { it.didConnect(bluetoothPeripheral) }
            //need to check via https
            bluetoothPeripheral.device.gatt.getPrimaryServices(null)
        } else {
            bluetoothPeripheral.device.gatt?.connect()?.then { gatt ->
                connect(bluetoothPeripheral, autoConnect)
            }
        }
    }

    @JsName("disconnect")
    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothPeripheral.device.gatt?.disconnect()
    }

    actual fun stopScanning() {}

    @JsName("scan")
    actual fun scan() {
        window.navigator.bluetooth.requestDevice(BluetoothOptions(true))
            .then { bluetoothDevice ->
                val device = BluetoothPeripheral(bluetoothDevice)
                delegates.forEach {
                    it.didDiscoverDevice(device)
                }
            }
    }

    /*@JsName("readService")
    fun readService(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUID: String?
    ) {
        bluetoothPeripheral.device.gatt?.getPrimaryServices(serviceUUID)?.then { services ->
            services.forEach {
                log("Service: ${it.uuid}")
            }
        }
    }*/

    @JsName("readCharacteristic")
    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        TODO("not implemented")
    }

    @JsName("writeCharacteristic")
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        TODO("not implemented")
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ){
        TODO("not implemented")
    }

    @JsName("notifyCharacteristic")
    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        TODO("not implemented")
    }

    @JsName("indicateCharacteristic")
    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        TODO("not implemented")
    }

    @JsName("notifyAndIndicateCharacteristic")
    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        TODO("not implemented")
    }

    @JsName("readDescriptor")
    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO("not implemented")
    }

    @JsName("changeMTU")
    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        TODO("not implemented")
    }

}
package dev.bluefalcon

import dev.bluefalcon.external.Bluetooth
import dev.bluefalcon.external.BluetoothOptions
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.w3c.dom.Navigator

@JsName("blueFalcon")
val blueFalcon = BlueFalcon(
    log = PrintLnLogger,
    context = ApplicationContext()
)

actual class BlueFalcon actual constructor(
    private val log: Logger,
    context: ApplicationContext
) {

    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    actual val scope = CoroutineScope(Dispatchers.Default)
    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)

    private inline val Navigator.bluetooth: Bluetooth get() = asDynamic().bluetooth as Bluetooth
    private var optionalServices: Array<String> = emptyArray()

    @JsName("addDelegate")
    fun addDelegate(blueFalconDelegate: BlueFalconDelegate) {
        delegates.add(blueFalconDelegate)
    }

    @JsName("removeDelegate")
    fun removeDelegate(blueFalconDelegate: BlueFalconDelegate) {
        delegates.remove(blueFalconDelegate)
    }

    @JsName("connect")
    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log.info("connect -> ${bluetoothPeripheral.device}:${bluetoothPeripheral.device.gatt} gatt connected? ${bluetoothPeripheral.device.gatt?.connected}")
        if (bluetoothPeripheral.device.gatt?.connected == true) {
            delegates.forEach { it.didConnect(bluetoothPeripheral) }
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
    fun scan(optionalServices: Array<String>) {
        this.optionalServices = optionalServices
        //scan()
    }

    @JsName("rescan")
    actual fun scan(serviceUUID : String?) {
        window.navigator.bluetooth.requestDevice(
            BluetoothOptions(
                false,
                arrayOf(BluetoothOptions.Filter.Services(optionalServices)),
                optionalServices
            )
        )
            .then { bluetoothDevice ->
                val device = BluetoothPeripheral(bluetoothDevice)

                val sharedAdvertisementData = mapOf(
                    AdvertisementDataRetrievalKeys.IsConnectable to 1,
                    AdvertisementDataRetrievalKeys.LocalName to "TODO",
                    AdvertisementDataRetrievalKeys.ServiceUUIDsKey to listOf<String>()
                ) //TODO Get real data

                delegates.forEach {
                    it.didDiscoverDevice(device, sharedAdvertisementData)
                }
            }
    }

    @JsName("readService")
    fun readService(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUID: String?
    ) {
        bluetoothPeripheral.device.gatt?.getPrimaryServices(serviceUUID)?.then { services ->
            bluetoothPeripheral._servicesFlow.tryEmit(
                (bluetoothPeripheral._servicesFlow.value + services.map { BluetoothService(it) })
                    .toSet()
                    .toList()
            )
            delegates.forEach {
                it.didDiscoverServices(bluetoothPeripheral)
            }
            bluetoothPeripheral.services.forEach { service ->
                service.service.getCharacteristics(undefined).then { characteristics ->
                    service._characteristicsFlow.tryEmit(characteristics.map { BluetoothCharacteristic(it) }.toSet().toList())
                    delegates.forEach {
                        it.didDiscoverCharacteristics(bluetoothPeripheral)
                    }
                }
            }
        }
    }

    @JsName("readCharacteristic")
    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothCharacteristic.characteristic.readValue().then { _ ->
            delegates.forEach {
                it.didCharacteristcValueChanged(bluetoothPeripheral, bluetoothCharacteristic)
            }
        }
    }


    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
    }

    @JsName("writeCharacteristic")
    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothCharacteristic.characteristic.writeValue(value)
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothCharacteristic.characteristic.writeValue(value)
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
package dev.bluefalcon

import dev.bluefalcon.external.Bluetooth
import dev.bluefalcon.external.BluetoothOptions
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.w3c.dom.Navigator

class JsBlueFalconEngine(
    private val log: Logger?,
    context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) : BlueFalconEngine {

    override val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    override var isScanning: Boolean = false

    override val scope = CoroutineScope(Dispatchers.Default)
    override val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)

    private inline val Navigator.bluetooth: Bluetooth get() = asDynamic().bluetooth as Bluetooth

    override val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)

    override fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) {
        // Not supported in Web Bluetooth API
    }

    override fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState =
        if (bluetoothPeripheral.device.gatt?.connected == true) {
            BluetoothPeripheralState.Connected
        } else {
            BluetoothPeripheralState.Disconnected
        }

    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log?.info("connect -> ${bluetoothPeripheral.device}:${bluetoothPeripheral.device.gatt} gatt connected? ${bluetoothPeripheral.device.gatt?.connected}")
        if (bluetoothPeripheral.device.gatt?.connected == true) {
            delegates.forEach { it.didConnect(bluetoothPeripheral) }
        } else {
            bluetoothPeripheral.device.gatt?.connect()?.then { gatt ->
                connect(bluetoothPeripheral, autoConnect)
            }
        }
    }

    override fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothPeripheral.device.gatt?.disconnect()
    }

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        // Not supported in Web Bluetooth API
        log?.warn("retrievePeripheral not supported in Web Bluetooth API")
        return null
    }

    override fun stopScanning() {}

    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    override fun scan(filters: List<ServiceFilter>) {
        window.navigator.bluetooth.requestDevice(
            BluetoothOptions(
                acceptAllDevices = false,
                filters = filters.map { BluetoothOptions.Filter.Services(it.serviceUuids) }.toTypedArray(),
                optionalServices = filters.flatMap { it.optionalServices.toList() }.toTypedArray()
            )
        )
            .then { bluetoothDevice ->
                val device = BluetoothPeripheralImpl(bluetoothDevice)

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

    override fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        readService(
            bluetoothPeripheral,
            serviceUUIDs.joinToString(",")
        )
    }
    override fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        if (!bluetoothPeripheral.services.containsKey(bluetoothService.uuid)) {
            readService(
                bluetoothPeripheral,
                bluetoothService.uuid.toString()
            )
        }
        // no need to do anything.
    }

    private fun readService(
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
            if (autoDiscoverAllServicesAndCharacteristics) {
                bluetoothPeripheral.services.values.forEach { service ->
                    service.service.getCharacteristics(undefined).then { characteristics ->
                        service._characteristicsFlow.tryEmit(characteristics.map { BluetoothCharacteristic(it) }.toSet().toList())
                        delegates.forEach {
                            it.didDiscoverCharacteristics(bluetoothPeripheral)
                        }
                    }
                }
            }
        }
    }

    override fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothCharacteristic.characteristic.readValue().then { _ ->
            delegates.forEach {
                it.didCharacteristcValueChanged(bluetoothPeripheral, bluetoothCharacteristic)
            }
        }
    }


    override fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
    }

    override fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothCharacteristic.characteristic.writeValue(value)
    }

    override fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothCharacteristic.characteristic.writeValue(value)
    }

    override fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        TODO("not implemented")
    }

    override fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        TODO("not implemented")
    }

    override fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        TODO("not implemented")
    }

    override fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO("not implemented")
    }

    override fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        TODO("not implemented")
    }

    override fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        TODO("not implemented")
    }

}

package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheralImpl actual constructor(actual override val device: NativeBluetoothDevice) :
    BluetoothPeripheral {
    
    private var _name: String? = null
    private var _rssi: Float? = null
    private var _mtuSize: Int? = null
    
    actual override val name: String?
        get() = _name ?: device.toString()
    
    actual override val uuid: String
        get() = device.toString()
    
    actual override var rssi: Float?
        get() = _rssi
        set(value) { _rssi = value }
    
    actual override var mtuSize: Int?
        get() = _mtuSize
        set(value) { _mtuSize = value }
    
    actual override val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }
    
    actual override val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    
    actual override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { service -> service.characteristics }
            .groupBy { characteristic -> characteristic.uuid }
            .mapValues { entry -> entry.value }
    
    fun setName(name: String?) {
        _name = name
    }
    
    override fun toString(): String = uuid
    
    override fun hashCode(): Int = uuid.hashCode()
    
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is BluetoothPeripheralImpl) return false
        return other.uuid == uuid
    }
}

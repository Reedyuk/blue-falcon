package dev.bluefalcon

actual object BlueFalcon : AbsBluetooth() {
    actual override val bluetooth: Bluetooth
        get() = getOrInitBluetooth()

    private var bluetoothRef: Bluetooth? = null

    actual fun init(bluetooth: Bluetooth) {
        if (bluetoothRef != null) throw IllegalStateException("Bluetooth already initialized")
        bluetoothRef = bluetooth
    }

    private fun getOrInitBluetooth(): Bluetooth = bluetoothRef ?: throw IllegalStateException("BlueFalcon.Init Method must be called first")

}
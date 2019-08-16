package uk.co.andrewreed

actual object BlueFalcon : AbsBluetooth() {
    actual override val bluetooth: Bluetooth
        get() = getOrInitBluetooth()

    private var bluetoothRef: Bluetooth? = null

    actual fun init(bluetooth: Bluetooth) {
        if (this.bluetoothRef != null)
            throw IllegalStateException("Bluetooth already initialized")
        this.bluetoothRef = bluetooth
    }

    private fun getOrInitBluetooth(): Bluetooth {
        val bluetooth = this.bluetoothRef
        if (bluetooth == null) {

            BlueFalcon.initDefault()
            return this.bluetoothRef!!
        }
        return bluetooth
    }
}
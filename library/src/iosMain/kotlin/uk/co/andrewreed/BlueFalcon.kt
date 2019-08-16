package uk.co.andrewreed

actual object BlueFalcon : AbsBluetooth() {
    actual override val bluetooth: Bluetooth
        get() = getOrInitBluetooth()

    private var bluetoothRef: Bluetooth? = null
    //private var bluetoothRef: AtomicReference<Bluetooth?> = AtomicReference(null)

    actual fun init(bluetooth: Bluetooth) {
        println("Bluetooth "+bluetooth)
        if (this.bluetoothRef != null)
            throw IllegalStateException("Bluetooth already initialized")
        println("Bluetooth after before"+bluetooth)
        this.bluetoothRef = bluetooth
        println("Bluetooth after "+bluetooth)

//        if (!this.bluetoothRef.compareAndSet(null, bluetooth.freeze()))
//            throw IllegalStateException("Bluetooth already initialized")
    }

    private fun getOrInitBluetooth(): Bluetooth {
//        val bluetooth = this.bluetoothRef.value
//        if (bluetooth == null) {
//            MultiBlue.initDefault()
//            return this.bluetoothRef.value!!
//        }
//        return bluetooth
        val bluetooth = this.bluetoothRef
        if (bluetooth == null) {
            BlueFalcon.initDefault()
            return this.bluetoothRef!!
        }
        return bluetooth
    }

}
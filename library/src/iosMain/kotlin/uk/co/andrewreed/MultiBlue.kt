package uk.co.andrewreed

import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.freeze

actual object MultiBlue : AbsBluetooth() {
    actual override val bluetooth: Bluetooth
        get() = getOrInitBluetooth()

    private var bluetoothRef: AtomicReference<Bluetooth?> = AtomicReference(null)

    actual fun init(bluetooth: Bluetooth) {
        if (!this.bluetoothRef.compareAndSet(null, bluetooth.freeze()))
            throw IllegalStateException("Bluetooth already initialized")
    }

    private fun getOrInitBluetooth(): Bluetooth {
        val bluetooth = this.bluetoothRef.value
        if (bluetooth == null) {

            MultiBlue.initDefault()
            return this.bluetoothRef.value!!
        }
        return bluetooth
    }

}
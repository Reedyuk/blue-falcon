package dev.bluefalcon

import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.freeze

actual object BlueFalcon : AbsBluetooth() {
    actual override val bluetooth: Bluetooth
        get() = getOrInitBluetooth()

    //private var bluetoothRef: Bluetooth? = null
    //private var bluetoothRef = AtomicReference<Bluetooth?>(null)
    private var bluetoothRef = AtomicReference<Bluetooth?>(getOrInitBluetooth())

    actual fun init(bluetooth: Bluetooth) {
        println("Bluetooth "+bluetooth)
        if (bluetoothRef.value != null)
            throw IllegalStateException("Bluetooth already initialized")
        println("Bluetooth after before"+bluetooth)
        bluetoothRef.value = bluetooth.freeze()
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
        val bluetooth = bluetoothRef.value
        if (bluetooth == null) {
            BlueFalcon.initDefault()
            return bluetoothRef.value!!
        }
        return bluetooth
    }

}
package dev.bluefalcon

import kotlinx.coroutines.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_t
import platform.posix.sleep
import kotlin.coroutines.CoroutineContext
import kotlin.native.concurrent.AtomicReference
import kotlin.native.concurrent.freeze

actual class BlueFalcon actual constructor(bluetooth: Bluetooth) : AbsBluetooth(bluetooth) {

    /*actual fun init(bluetooth: Bluetooth) {
        println("Bluetooth "+bluetooth)
        if (bluetoothRef != null)
            throw IllegalStateException("Bluetooth already initialized")
        println("Bluetooth after before"+bluetooth)
        bluetoothRef = bluetooth.freeze()
        println("Bluetooth after "+bluetooth)

//        if (!this.bluetoothRef.compareAndSet(null, bluetooth.freeze()))
//            throw IllegalStateException("Bluetooth already initialized")
    }*/

    /*private fun getOrInitBluetooth(): Bluetooth {
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



        var bluetooth: Bluetooth? = null
        println("attempting")
        runBlocking {
            GlobalScope.launch(ApplicationDispatcher) {
                println("global")
                bluetooth = bluetoothRef
                if (bluetooth == null) {
                    BlueFalcon.initDefault()
                    bluetooth = bluetoothRef!!
                }
            }
        }
        sleep(5u)

        println("finished")
        return bluetooth!!
    }
*/


}
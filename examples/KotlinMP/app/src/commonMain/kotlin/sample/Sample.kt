package sample

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalcon

expect class Sample() {
    fun checkMe(): Int
}

expect object Platform {
    val name: String
}

expect class Blue(context: ApplicationContext) {
    val blueFalcon: BlueFalcon
}

fun hello(): String = "Hello from ${Platform.name}"

class Proxy {
    fun proxyHello() = hello()
}

fun main() {
    println(hello())
//    val bluetoothService = BluetoothService()
//    bluetoothService.scan()
}
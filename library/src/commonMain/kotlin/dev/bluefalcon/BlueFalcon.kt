package dev.bluefalcon

expect object BlueFalcon: AbsBluetooth {

    override val bluetooth: Bluetooth

    fun init(bluetooth: Bluetooth)
}

fun BlueFalcon.initDefault() {
    init(PlatformBluetooth())
}
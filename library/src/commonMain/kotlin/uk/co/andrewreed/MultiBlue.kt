package uk.co.andrewreed

expect object MultiBlue: AbsBluetooth {

    override val bluetooth: Bluetooth

    fun init(bluetooth: Bluetooth)
}

fun MultiBlue.initDefault() {
    init(PlatformBluetooth())
}
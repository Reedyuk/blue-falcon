package dev.bluefalcon

import android.bluetooth.BluetoothManager
import android.content.Context

actual class PlatformBluetooth actual constructor() : Bluetooth {

    private var platformContext: PlatformContext? = null

    constructor(platformContext: PlatformContext) : this() {
        this.platformContext = platformContext
    }

    override fun connect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun scan() {
        println("Scan")
        val context = platformContext?.getContext() as Context
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter.startDiscovery()
    }
}
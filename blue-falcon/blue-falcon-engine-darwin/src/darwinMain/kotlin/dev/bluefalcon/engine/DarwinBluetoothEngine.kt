package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalcon

class DarwinBluetoothEngine(
    override val config: DarwinBluetoothEngineConfig
) : BluetoothEngineBase("DarwinEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        config.context
    ).also { it.delegates.add(config.bluetoothCallbackDelegate) }

    private fun getDevice(device: String) = blueFalcon.peripherals.value.first { peripheral -> peripheral.uuid == device }

    override suspend fun execute(action: BluetoothAction) {
        when (action) {
            is BluetoothAction.Connect -> {
                 blueFalcon.connect(getDevice(action.device))
            }
            is BluetoothAction.Disconnect -> {
                blueFalcon.disconnect(getDevice(action.device))
            }
            is BluetoothAction.Scan -> {
                println("Scanning")
                blueFalcon.scan(action.filters)
            }
        }
    }
}

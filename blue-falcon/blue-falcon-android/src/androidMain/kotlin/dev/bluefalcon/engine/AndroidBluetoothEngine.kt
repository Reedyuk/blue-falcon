package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalcon

class AndroidBluetoothEngine(
    override val config: AndroidBluetoothEngineConfig
) : BluetoothEngineBase("AndroidBluetoothEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        config.context,
        config.autoDiscoverAllServicesAndCharacteristics
    ).also { it.delegates.add(config.bluetoothCallbackDelegate) }

    override suspend fun execute(action: BluetoothAction) {
        when (action) {
            is BluetoothAction.Connect -> {
//                blueFalcon.connect(action.device)
            }
            is BluetoothAction.Disconnect -> {
            }
            is BluetoothAction.Scan -> {
                blueFalcon.scan(action.filters)
            }
        }
    }


}

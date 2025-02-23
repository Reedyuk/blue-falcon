package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalcon

class JsBluetoothEngine(
    override val config: DarwinBluetoothEngineConfig
) : BluetoothEngineBase("JsEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        config.context
    ).also { it.delegates.add(config.bluetoothCallbackDelegate) }

    override suspend fun execute(action: BluetoothAction) {
        when (action) {
            is BluetoothAction.Connect -> {
                // blueFalcon.connect(action.device)
            }
            is BluetoothAction.Disconnect -> {

            }
            is BluetoothAction.Scan -> {
                 blueFalcon.scan(action.filters)
            }
        }
    }
}

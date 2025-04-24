package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class JsBluetoothEngine(
    override val config: JsBluetoothEngineConfig
) : BluetoothEngineBase("JsEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        false
    ).also { config.bluetoothCallbackDelegate?.let { it1 -> it.delegates.add(it1) } }

    override suspend fun execute(action: BluetoothAction): Flow<BluetoothActionResult> {
        when (action) {
            is BluetoothAction.Connect -> {
                // blueFalcon.connect(action.device)
            }
            is BluetoothAction.Disconnect -> {

            }
            is BluetoothAction.Scan -> {
                 blueFalcon.scan(action.filters)
            }
            is BluetoothAction.StopScan -> TODO()
            is BluetoothAction.ReadCharacteristic -> TODO()
            is BluetoothAction.WriteCharacteristic -> TODO()
            is BluetoothAction.DiscoverCharacteristics -> TODO()
            is BluetoothAction.DiscoverServices -> TODO()
            is BluetoothAction.IndicateCharacteristic -> TODO()
            is BluetoothAction.NotifyCharacteristic -> TODO()
            is BluetoothAction.SetMtu -> TODO()
        }
        return emptyFlow()
    }
}

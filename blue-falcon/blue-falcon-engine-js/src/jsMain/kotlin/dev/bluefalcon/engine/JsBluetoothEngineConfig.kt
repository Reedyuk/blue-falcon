package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate

class JsBluetoothEngineConfig(
    context: ApplicationContext,
    bluetoothCallbackDelegate: BlueFalconDelegate,
) : BluetoothEngineConfig(context, bluetoothCallbackDelegate) {
}
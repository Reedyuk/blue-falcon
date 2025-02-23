package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate

class DarwinBluetoothEngineConfig(
    context: ApplicationContext,
    bluetoothCallbackDelegate: BlueFalconDelegate,
) : BluetoothEngineConfig(context, bluetoothCallbackDelegate) {
}
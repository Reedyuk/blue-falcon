package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate

class AndroidBluetoothEngineConfig(
    context: ApplicationContext,
    bluetoothCallbackDelegate: BlueFalconDelegate,
) : BluetoothEngineConfig(context, bluetoothCallbackDelegate) {
    var autoDiscoverAllServicesAndCharacteristics: Boolean = false
}
package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger

open class BluetoothEngineConfig(
    val context: ApplicationContext,
    val bluetoothCallbackDelegate: BlueFalconDelegate,
    val logger: Logger? = null
) {

}

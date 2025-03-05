package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger
import kotlin.Boolean

open class BluetoothEngineConfig(
    val bluetoothCallbackDelegate: BlueFalconDelegate,
    val logger: Logger? = null,
) {
}

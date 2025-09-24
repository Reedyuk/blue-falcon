package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.Boolean

open class BluetoothEngineConfig(
    val bluetoothCallbackDelegate: BlueFalconDelegate? = null,
    val dispatcher: CoroutineDispatcher? = null,
    val logger: Logger? = null,
) {
}

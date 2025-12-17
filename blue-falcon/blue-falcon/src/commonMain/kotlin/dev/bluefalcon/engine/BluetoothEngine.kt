package dev.bluefalcon.engine

import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.Logger
import dev.bluefalcon.engine.BluetoothEngine

expect fun blueFalconEngine(
    context: ApplicationContext,
    delegate: BlueFalconDelegate? = null,
    logger: Logger? = null
): BluetoothEngine

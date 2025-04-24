package dev.bluefalcon.engine

import dev.bluefalcon.Logger
import dev.bluefalcon.engine.JsBluetoothEngine
import dev.bluefalcon.engine.JsBluetoothEngineConfig

actual fun blueFalconEngine(
    context: dev.bluefalcon.ApplicationContext,
    delegate: dev.bluefalcon.BlueFalconDelegate?,
    logger: Logger?
): BluetoothEngine = JsBluetoothEngine(
    config = JsBluetoothEngineConfig(
        bluetoothCallbackDelegate = delegate,
        logger = logger
    )
)

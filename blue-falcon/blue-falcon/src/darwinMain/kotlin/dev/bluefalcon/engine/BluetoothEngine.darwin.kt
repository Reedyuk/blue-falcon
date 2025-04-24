package dev.bluefalcon.engine

import dev.bluefalcon.Logger

actual fun blueFalconEngine(
    context: dev.bluefalcon.ApplicationContext,
    delegate: dev.bluefalcon.BlueFalconDelegate?,
    logger: Logger?
): BluetoothEngine = DarwinBluetoothEngine(
    config = DarwinBluetoothEngineConfig(
        bluetoothCallbackDelegate = delegate,
        logger = logger
    )
)

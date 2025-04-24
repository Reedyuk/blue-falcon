package dev.bluefalcon.engine

import dev.bluefalcon.Logger

actual fun blueFalconEngine(
    context: dev.bluefalcon.ApplicationContext,
    delegate: dev.bluefalcon.BlueFalconDelegate?,
    logger: Logger?
): BluetoothEngine = AndroidBluetoothEngine(
    config = AndroidBluetoothEngineConfig(
        context = context,
        bluetoothCallbackDelegate = delegate,
        logger = logger
    )
)

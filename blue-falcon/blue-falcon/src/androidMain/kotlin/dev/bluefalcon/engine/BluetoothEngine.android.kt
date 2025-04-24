package dev.bluefalcon.engine

actual fun blueFalconEngine(
    context: dev.bluefalcon.ApplicationContext,
    delegate: dev.bluefalcon.BlueFalconDelegate?
): BluetoothEngine = AndroidBluetoothEngine(
    config = AndroidBluetoothEngineConfig(
        context = context,
        bluetoothCallbackDelegate = delegate
    )
)

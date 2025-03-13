package dev.bluefalcon.engine

actual fun blueFalconEngine(
    context: dev.bluefalcon.ApplicationContext,
    delegate: dev.bluefalcon.BlueFalconDelegate
): BluetoothEngine = DarwinBluetoothEngine(
    config = DarwinBluetoothEngineConfig(
        bluetoothCallbackDelegate = delegate
    )
)

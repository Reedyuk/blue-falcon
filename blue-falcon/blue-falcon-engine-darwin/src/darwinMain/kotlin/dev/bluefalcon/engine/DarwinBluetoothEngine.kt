package dev.bluefalcon.engine

import dev.bluefalcon.BlueFalcon

class DarwinBluetoothEngine(
    override val config: DarwinBluetoothEngineConfig
) : BluetoothEngineBase("DarwinEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        config.autoDiscoverAllServicesAndCharacteristics
    ).also { it.delegates.add(config.bluetoothCallbackDelegate) }

    private fun getDevice(device: String) = blueFalcon.peripherals.value.first { peripheral -> peripheral.uuid == device }

    override suspend fun execute(action: BluetoothAction) {
        when (action) {
            is BluetoothAction.Connect -> {
                 blueFalcon.connect(getDevice(action.device))
            }
            is BluetoothAction.Disconnect -> {
                blueFalcon.disconnect(getDevice(action.device))
            }
            is BluetoothAction.Scan -> {
                blueFalcon.scan(action.filters)
            }
            is BluetoothAction.StopScan -> {
                blueFalcon.stopScanning()
            }

            is BluetoothAction.ReadCharacteristic -> {
                getDevice(action.device).let { device ->
                    blueFalcon.readCharacteristic(
                        device,
                        device.characteristics.getValue(action.characteristic)
                    )
                }
            }

            is BluetoothAction.WriteCharacteristic -> {
                getDevice(action.device).let { device ->
                    blueFalcon.writeCharacteristic(
                        device,
                        device.characteristics.getValue(action.characteristic),
                        action.value,
                        when(action.writeType) {
                            WriteType.writeTypeDefault -> 0
                            WriteType.writeTypeNoResponse -> 1
                        }
                    )
                }
            }

            is BluetoothAction.DiscoverCharacteristics -> {
                getDevice(action.device).let { device ->
                    blueFalcon.discoverCharacteristics(
                        device,
                        device.services.getValue(action.service),
                        action.characteristicUUIDs
                    )
                }
            }
            is BluetoothAction.DiscoverServices -> {
                getDevice(action.device).let { device ->
                    blueFalcon.discoverServices(
                        device,
                        action.serviceUUIDs
                    )
                }
            }
        }
    }
}

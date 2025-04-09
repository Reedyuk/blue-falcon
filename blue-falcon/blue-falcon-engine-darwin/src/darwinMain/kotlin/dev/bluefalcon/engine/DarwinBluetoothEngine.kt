package dev.bluefalcon.engine

import dev.bluefalcon.AdvertisementDataRetrievalKeys
import dev.bluefalcon.BTCharacteristic
import dev.bluefalcon.BTService
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothDevice
import dev.bluefalcon.BluetoothPeripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class DarwinBluetoothEngine(
    override val config: DarwinBluetoothEngineConfig
) : BluetoothEngineBase("DarwinEngine") {

    private val blueFalcon: BlueFalcon = BlueFalcon(
        config.logger,
        config.autoDiscoverAllServicesAndCharacteristics
    ).also { it.delegates.add(config.bluetoothCallbackDelegate) }

    private fun getDevice(device: String) = blueFalcon.peripherals.value.first { peripheral -> peripheral.uuid == device }

    override suspend fun execute(action: BluetoothAction): Flow<BluetoothActionResult> {
        val resultFlow: MutableSharedFlow<BluetoothActionResult> = MutableSharedFlow()
        when (action) {
            is BluetoothAction.Connect -> {
                blueFalcon.delegates.add(
                    object : BlueFalconDelegate {
                        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
                            CoroutineScope(Dispatchers.IO).launch {
                                resultFlow.emit(
                                    BluetoothActionResult.Connect(
                                        device =
                                            BluetoothDevice(
                                                bluetoothPeripheral.uuid,
                                                bluetoothPeripheral.name,
                                                bluetoothPeripheral.rssi,
                                                bluetoothPeripheral.mtuSize
                                            )
                                    )
                                )
                            }
                        }
                    }
                )
                 blueFalcon.connect(getDevice(action.device))
            }
            is BluetoothAction.Disconnect -> {
                blueFalcon.disconnect(getDevice(action.device))
            }
            is BluetoothAction.Scan -> {
                blueFalcon.delegates.add(
                    object : BlueFalconDelegate {
                        override fun didDiscoverDevice(
                            bluetoothPeripheral: BluetoothPeripheral,
                            advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
                        ) {
                            CoroutineScope(Dispatchers.IO).launch {
                                resultFlow.emit(
                                    BluetoothActionResult.Scan(
                                        device =
                                            BluetoothDevice(
                                                bluetoothPeripheral.uuid,
                                                bluetoothPeripheral.name,
                                                bluetoothPeripheral.rssi
                                            )
                                    )
                                )
                            }
                        }
                    }
                )
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
                blueFalcon.delegates.add(object : BlueFalconDelegate {
                    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
                        CoroutineScope(Dispatchers.IO).launch {
                            resultFlow.emit(
                                BluetoothActionResult.DiscoverCharacteristics(
                                    device = BluetoothDevice(
                                        bluetoothPeripheral.uuid,
                                        bluetoothPeripheral.name,
                                        bluetoothPeripheral.rssi,
                                        bluetoothPeripheral.mtuSize,
                                        bluetoothPeripheral.services.map {
                                            BTService(
                                                it.key,
                                                it.value.name,
                                                it.value.characteristics.map { characteristic ->
                                                    BTCharacteristic(
                                                        characteristic.uuid,
                                                        characteristic.name,
                                                        characteristic.value
                                                    )
                                                }
                                            )
                                        }
                                    )
                                )
                            )
                        }
                    }
                })
                val device = getDevice(action.device)
                val service = device.services.getValue(action.service)
                blueFalcon.discoverCharacteristics(device, service, action.characteristicUUIDs)
            }

            is BluetoothAction.DiscoverServices -> {
                blueFalcon.delegates.add(object : BlueFalconDelegate {
                    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
                        CoroutineScope(Dispatchers.IO).launch {
                            resultFlow.emit(
                                BluetoothActionResult.DiscoverServices(
                                    device = BluetoothDevice(
                                        bluetoothPeripheral.uuid,
                                        bluetoothPeripheral.name,
                                        bluetoothPeripheral.rssi,
                                        bluetoothPeripheral.mtuSize,
                                        bluetoothPeripheral.services.map {
                                            BTService(it.key, it.value.name)
                                        }
                                    )
                                )
                            )
                        }
                    }
                })
                blueFalcon.discoverServices(getDevice(action.device), action.serviceUUIDs)
            }
        }
        return resultFlow
    }
}

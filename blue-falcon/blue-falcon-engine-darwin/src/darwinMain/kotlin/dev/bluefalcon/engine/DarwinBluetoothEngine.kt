package dev.bluefalcon.engine

import dev.bluefalcon.AdvertisementDataRetrievalKeys
import dev.bluefalcon.BTCharacteristic
import dev.bluefalcon.BTService
import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothDevice
import dev.bluefalcon.BluetoothPeripheral
import kotlinx.coroutines.CoroutineDispatcher
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
    ).also { config.bluetoothCallbackDelegate?.let { it1 -> it.delegates.add(it1) } }

    private fun getDevice(device: String) = blueFalcon.peripherals.value.first { peripheral -> peripheral.uuid == device }

    override fun execute(action: BluetoothAction): Flow<BluetoothActionResult> {
        config.logger?.info("Executing action: $action")
        val resultFlow: MutableSharedFlow<BluetoothActionResult> = MutableSharedFlow()
        when (action) {
            is BluetoothAction.Connect -> {
                blueFalcon.delegates.add(
                    object : BlueFalconDelegate {
                        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
                            CoroutineScope(coroutineContext).launch {
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
                resultFlow.tryEmit(BluetoothActionResult.Success)
            }
            is BluetoothAction.Scan -> {
                blueFalcon.delegates.add(
                    object : BlueFalconDelegate {
                        override fun didDiscoverDevice(
                            bluetoothPeripheral: BluetoothPeripheral,
                            advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
                        ) {
                            CoroutineScope(coroutineContext).launch {
                                resultFlow.emit(
                                    BluetoothActionResult.Scan(
                                        device =
                                            BluetoothDevice(
                                                bluetoothPeripheral.uuid,
                                                bluetoothPeripheral.name,
                                                bluetoothPeripheral.rssi
                                            ),
                                        advertisementInfo = advertisementData
                                    )
                                )
                            }
                        }
                    }
                )
                blueFalcon.scan(action.filters)
                // issue is multiple scan jobs...
            }
            is BluetoothAction.StopScan -> {
                blueFalcon.stopScanning()
                // could we cycle through all the delegates, remove ones that are subscribed to scanning.
                resultFlow.tryEmit(BluetoothActionResult.Success)
            }

            is BluetoothAction.ReadCharacteristic -> {
                // same issue here, where it will constantly get notified.
                val device = getDevice(action.device)
                val actionCharacteristic = device.characteristics.getValue(action.characteristic)
                blueFalcon.delegates.add(object : BlueFalconDelegate {
                    override fun didCharacteristcValueChanged(
                        bluetoothPeripheral: BluetoothPeripheral,
                        bluetoothCharacteristic: BluetoothCharacteristic
                    ) {
                        if (bluetoothCharacteristic.uuid != actionCharacteristic.uuid) return
                        CoroutineScope(coroutineContext).launch {
                            resultFlow.emit(
                                BluetoothActionResult.ReadCharacteristic(
                                    device = BluetoothDevice(
                                        bluetoothPeripheral.uuid,
                                        bluetoothPeripheral.name,
                                        bluetoothPeripheral.rssi,
                                        bluetoothPeripheral.mtuSize
                                    ),
                                    characteristic = BTCharacteristic(
                                        bluetoothCharacteristic.uuid,
                                        bluetoothCharacteristic.name,
                                        bluetoothCharacteristic.value
                                    ),
                                    value = bluetoothCharacteristic.value
                                )
                            )
                        }
                    }
                })
                blueFalcon.readCharacteristic(
                    device,
                    actionCharacteristic
                )
            }

            is BluetoothAction.WriteCharacteristic -> {
                blueFalcon.delegates.add(object : BlueFalconDelegate {
                    override fun didWriteCharacteristic(
                        bluetoothPeripheral: BluetoothPeripheral,
                        bluetoothCharacteristic: BluetoothCharacteristic,
                        success: Boolean
                    ) {
                        CoroutineScope(coroutineContext).launch {
                            resultFlow.emit(BluetoothActionResult.Success)
                        }
                    }
                })
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
                        CoroutineScope(coroutineContext).launch {
                            val characteristics = bluetoothPeripheral.services.flatMap { service ->
                                service.value.characteristics.map { characteristic ->
                                    BTCharacteristic(
                                        characteristic.uuid,
                                        characteristic.name,
                                        characteristic.value
                                    )
                                }
                            }
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
                                                characteristics
                                            )
                                        }
                                    ),
                                    characteristics = characteristics
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
                        CoroutineScope(coroutineContext).launch {
                            val services = bluetoothPeripheral.services.map {
                                BTService(it.key, it.value.name)
                            }
                            resultFlow.emit(
                                BluetoothActionResult.DiscoverServices(
                                    device = BluetoothDevice(
                                        bluetoothPeripheral.uuid,
                                        bluetoothPeripheral.name,
                                        bluetoothPeripheral.rssi,
                                        bluetoothPeripheral.mtuSize,
                                        services = services
                                    ),
                                    services = services
                                )
                            )
                        }
                    }
                })
                blueFalcon.discoverServices(getDevice(action.device), action.serviceUUIDs)
            }

            is BluetoothAction.IndicateCharacteristic -> {
                val device = getDevice(action.device)
                val characteristic = device.characteristics.getValue(action.characteristic)
                blueFalcon.indicateCharacteristic(device, characteristic, action.indicate)
                CoroutineScope(coroutineContext).launch {
                    resultFlow.emit(BluetoothActionResult.Success)
                }
            }
            is BluetoothAction.NotifyCharacteristic -> {
                val device = getDevice(action.device)
                val characteristic = device.characteristics.getValue(action.characteristic)
                blueFalcon.notifyCharacteristic(device, characteristic, action.notify)
                CoroutineScope(coroutineContext).launch {
                    resultFlow.emit(BluetoothActionResult.Success)
                }
            }
            is BluetoothAction.SetMtu -> {
                val device = getDevice(action.device)
                blueFalcon.delegates.add(object : BlueFalconDelegate {
                    override fun didUpdateMTU(
                        bluetoothPeripheral: BluetoothPeripheral,
                        status: Int
                    ) {
                        CoroutineScope(coroutineContext).launch {
                            resultFlow.emit(BluetoothActionResult.MtuChanged(
                                device = BluetoothDevice(
                                    bluetoothPeripheral.uuid,
                                    bluetoothPeripheral.name,
                                    bluetoothPeripheral.rssi,
                                    bluetoothPeripheral.mtuSize
                                ),
                                status = status
                            ))
                        }
                    }
                })
                blueFalcon.changeMTU(device, mtuSize = action.mtu)
            }
        }
        return resultFlow
    }
}

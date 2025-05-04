package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.engine.blueFalconEngine
import dev.bluefalcon.engine.WriteType
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.ApplicationContext
import dev.bluefalcon.Logger
import dev.bluefalcon.engine.BluetoothAction
import dev.bluefalcon.engine.BluetoothActionResult
import dev.icerock.moko.mvvm.viewmodel.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BluetoothDeviceViewModel(
    applicationContext: ApplicationContext,
): ViewModel() {

    private val _deviceState: MutableStateFlow<BluetoothDeviceState> = MutableStateFlow(BluetoothDeviceState())
    val deviceState: StateFlow<BluetoothDeviceState> get() = _deviceState

    private val blueFalconEngine = blueFalconEngine(
        context = applicationContext,
        logger = object : Logger {
            override fun debug(message: String, cause: Throwable?) {
                println("DEBUG: $message")
            }

            override fun error(message: String, cause: Throwable?) {
                println("ERROR: $message")
            }

            override fun info(message: String, cause: Throwable?) {
                println("INFO: $message")
            }

            override fun warn(message: String, cause: Throwable?) {
                println("WARN: $message")
            }
        }
    )

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun onEvent(event: UiEvent) {
        when(event) {
            UiEvent.OnScanClick -> {
                _deviceState.value.let { deviceState ->
                    _deviceState.tryEmit(deviceState.copy(isScanning = !deviceState.isScanning))
                }
                CoroutineScope(Dispatchers.IO).launch {
                    if (!_deviceState.value.isScanning) {
                        blueFalconEngine.execute(BluetoothAction.StopScan)
                    } else {
                        blueFalconEngine.execute(BluetoothAction.Scan()).collect {
                            when (it) {
                                is BluetoothActionResult.Scan -> {
                                    _deviceState.update { state ->
                                        val updateDevices = state.devices.toMutableMap()
                                        updateDevices[it.device.uuid] = EnhancedBluetoothPeripheral(
                                            false,
                                            it.device,
                                            it.advertisementInfo.map { (key, value) ->
                                                key to when (value) {
                                                    is String -> value
                                                    is ByteArray -> value.contentToString()
                                                    else -> value.toString()
                                                }
                                            }.toMap(),
                                            false
                                        )
                                        state.copy(devices = HashMap(updateDevices))
                                    }
                                }

                                else -> {}
                            }
                        }
                    }
                }
            }

            is UiEvent.OnConnectClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    blueFalconEngine.execute(BluetoothAction.Connect(event.macId)).collect { connectState ->
                        when (connectState) {
                            is BluetoothActionResult.Connect -> {
                                _deviceState.update { state ->
                                    val updateDevices = state.devices.toMutableMap()
                                    updateDevices[event.macId]?.let {
                                        updateDevices[event.macId] = it.copy(
                                            connected = true,
                                            showDetails = true
                                        )
                                    }
                                    state.copy(devices = HashMap(updateDevices))
                                }
                            }
                            else -> {}
                        }
                        blueFalconEngine.execute(BluetoothAction.DiscoverServices(event.macId)).collect { discoverServiceState ->
                            when (discoverServiceState) {
                                is BluetoothActionResult.DiscoverServices -> {
                                    discoverServiceState.device.services.forEach { service ->
                                        blueFalconEngine.execute(
                                            BluetoothAction.DiscoverCharacteristics(event.macId, service.uuid)
                                        ).collect { discoverState ->
                                            when (discoverState) {
                                                is BluetoothActionResult.DiscoverCharacteristics -> {
                                                    _deviceState.update { state ->
                                                        val updateDevices =
                                                            state.devices.toMutableMap()
                                                        updateDevices[event.macId]?.let {
                                                            updateDevices[event.macId] = it.copy(peripheral = discoverState.device)
                                                        }
                                                        state.copy(devices = HashMap(updateDevices))
                                                    }
                                                }

                                                else -> {}
                                            }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            is UiEvent.OnDisconnectClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(BluetoothAction.Disconnect(it.peripheral.uuid))
                    }
                }
            }

            is UiEvent.OnReadCharacteristic -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let { device ->
                        blueFalconEngine.execute(
                            BluetoothAction.ReadCharacteristic(
                                device.peripheral.uuid,
                                event.characteristic.uuid
                            )
                        ).collect {
                            when (it) {
                                is BluetoothActionResult.ReadCharacteristic -> {
                                    _deviceState.update { state ->
                                        val updateDevices = state.devices.toMutableMap()
                                        updateDevices[event.macId]?.let { device ->
                                            val updatedDevice = device.copy(
                                                peripheral = device.peripheral.copy(
                                                    services = device.peripheral.services.map { service ->
                                                        service.copy(
                                                            characteristics = service.characteristics.map { characteristic ->
                                                                if (characteristic.uuid == event.characteristic.uuid) {
                                                                    characteristic.copy(value = it.value)
                                                                } else {
                                                                    characteristic
                                                                }
                                                            }
                                                        )
                                                    },
                                                )
                                            )
                                            updateDevices[event.macId] = updatedDevice
                                        }
                                        state.copy(devices = HashMap(updateDevices))
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
            is UiEvent.OnWriteCharacteristic -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let {
                        blueFalconEngine.execute(
                            BluetoothAction.WriteCharacteristic(
                                it.peripheral.uuid,
                                event.characteristic.uuid,
                                event.value.encodeToByteArray(),
                                WriteType.writeTypeDefault
                            )
                        )
                    }
                }
            }

            is UiEvent.OnShowDetailsClick -> {
                CoroutineScope(Dispatchers.IO).launch {
                    _deviceState.value.devices[event.macId]?.let { device ->
                        _deviceState.update { state ->
                            val updateDevices = state.devices.toMutableMap()
                            updateDevices[event.macId]?.let {
                                updateDevices[event.macId] = it.copy(showDetails = !it.showDetails)
                            }
                            state.copy(devices = HashMap(updateDevices))
                        }
                    }
                }
            }

            is UiEvent.OnExportDetailsClick -> {
                // TODO: Implement export functionality - export to json services and characteristics
            }
        }
    }
}
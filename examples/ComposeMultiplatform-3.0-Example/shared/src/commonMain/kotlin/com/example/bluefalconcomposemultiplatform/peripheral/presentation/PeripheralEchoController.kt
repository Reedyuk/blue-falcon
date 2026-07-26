package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.EchoGatt
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.PeripheralConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PeripheralEchoController(
    private val runtime: PeripheralExampleRuntime?,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        PeripheralServerState(supported = runtime != null),
    )
    val state: StateFlow<PeripheralServerState> = mutableState.asStateFlow()

    private val config = echoConfig()
    private var subscriptionObserverJob: Job? = null

    init {
        if (runtime != null) {
            scope.launch {
                runtime.manager.state.collect { managerState ->
                    mutableState.update { current ->
                        current.copy(managerState = managerState)
                    }
                }
            }
            scope.launch {
                runtime.manager.sessions.collect { sessions ->
                    subscriptionObserverJob?.cancelAndJoin()

                    val subscribedSessionCount = sessions.count { session ->
                        EchoGatt.characteristicId in session.subscriptions.value
                    }
                    mutableState.update { current ->
                        current.copy(
                            sessionCount = sessions.size,
                            subscribedSessionCount = subscribedSessionCount,
                        )
                    }

                    subscriptionObserverJob = if (sessions.isEmpty()) {
                        null
                    } else {
                        launch {
                            combine(sessions.map { session -> session.subscriptions }) {
                                subscriptions ->
                                subscriptions.count { subscription ->
                                    EchoGatt.characteristicId in subscription
                                }
                            }.collect { count ->
                                mutableState.update { current ->
                                    current.copy(subscribedSessionCount = count)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun start() {
        val manager = runtime?.manager ?: return
        try {
            manager.start(config)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            appendLog("Start failed: ${cause.message ?: "unknown error"}")
        }
    }

    suspend fun stop() {
        val manager = runtime?.manager ?: return
        try {
            manager.stop()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            appendLog("Stop failed: ${cause.message ?: "unknown error"}")
        }
    }

    private fun appendLog(message: String) {
        mutableState.update { current ->
            current.copy(
                log = (current.log + message).takeLast(MAX_LOG_ENTRIES),
            )
        }
    }
}

private fun echoConfig() = PeripheralConfig(
    advertiseConfig = AdvertiseConfig(
        localName = "Blue Falcon Echo",
        serviceUuids = listOf(EchoGatt.serviceUuid),
        services = listOf(
            GattServiceConfig(
                uuid = EchoGatt.serviceUuid,
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = EchoGatt.characteristicUuid,
                        properties = setOf(
                            CharacteristicProperty.READ,
                            CharacteristicProperty.WRITE,
                            CharacteristicProperty.WRITE_NO_RESPONSE,
                            CharacteristicProperty.NOTIFY,
                            CharacteristicProperty.INDICATE,
                        ),
                        initialValue = DEFAULT_ECHO_VALUE,
                    ),
                ),
            ),
        ),
    ),
    restorationIdentifier = EchoGatt.restorationIdentifier,
)

private val DEFAULT_ECHO_VALUE = "Hello from Blue Falcon".encodeToByteArray()
private const val MAX_LOG_ENTRIES = 100

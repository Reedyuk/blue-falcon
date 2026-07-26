package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import dev.bluefalcon.peripheral.PeripheralManagerState

data class PeripheralServerState(
    val supported: Boolean,
    val managerState: PeripheralManagerState = PeripheralManagerState.Stopped,
    val sessionCount: Int = 0,
    val subscribedSessionCount: Int = 0,
    val payloadText: String = "Hello from Blue Falcon",
    val log: List<String> = emptyList(),
) {
    val canStart get() = supported && managerState == PeripheralManagerState.Stopped
    val canStop
        get() = supported && (
            managerState == PeripheralManagerState.Running ||
                managerState is PeripheralManagerState.Failed
        )
    val canSend
        get() = supported &&
            managerState == PeripheralManagerState.Running &&
            subscribedSessionCount > 0 &&
            payloadText.isNotEmpty()
}

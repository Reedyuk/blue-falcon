package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import dev.bluefalcon.peripheral.PeripheralManagerState

data class PeripheralServerState(
    val supported: Boolean,
    val profile: PeripheralProfile = PeripheralProfile.ECHO,
    val managerState: PeripheralManagerState = PeripheralManagerState.Stopped,
    val sessionCount: Int = 0,
    val subscribedSessionCount: Int = 0,
    val payloadText: String = "Hello from Blue Falcon",
    val heartRateBpm: Int = DEFAULT_HEART_RATE_BPM,
    val simulatingHeartRate: Boolean = false,
    val bondingRequired: Boolean = false,
    val bondOnHeartRateRead: Boolean = false,
    val bondedSessionCount: Int = 0,
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
    val canToggleHeartRateSimulation
        get() = supported && managerState == PeripheralManagerState.Running
    val canSwitchProfile get() = supported && managerState == PeripheralManagerState.Stopped
    val canToggleBondingRequirement
        get() = supported &&
            profile == PeripheralProfile.HEART_RATE_MONITOR &&
            managerState == PeripheralManagerState.Stopped
    val canToggleBondOnHeartRateRead
        get() = supported &&
            profile == PeripheralProfile.HEART_RATE_MONITOR &&
            managerState == PeripheralManagerState.Stopped

    companion object {
        const val DEFAULT_HEART_RATE_BPM = 70
    }
}

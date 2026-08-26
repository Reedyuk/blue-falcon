package com.example.bluefalconcomposemultiplatform.peripheral.presentation

/**
 * The GATT profile currently hosted by the peripheral example's local GATT server.
 *
 * Only one profile can be advertised at a time. Switching profiles is only permitted
 * while the server is stopped.
 */
enum class PeripheralProfile {
    ECHO,
    HEART_RATE_MONITOR,
}

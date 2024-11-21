package dev.bluefalcon

import java.util.UUID

actual data class ServiceFilter(
    val serviceUuids: List<UUID> = emptyList(),
)
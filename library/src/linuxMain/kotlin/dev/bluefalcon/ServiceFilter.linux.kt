package dev.bluefalcon

actual data class ServiceFilter(
    val serviceUuids: List<Uuid> = emptyList(),
)

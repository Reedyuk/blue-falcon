package dev.bluefalcon

actual data class ServiceFilter(
    val serviceUuids: Array<String> = emptyArray(),
    val optionalServices: Array<String> = emptyArray(),
)
package dev.bluefalcon

import android.os.ParcelUuid

actual data class ServiceFilter(
    val serviceUuids: List<ParcelUuid> = emptyList(),
    val serviceData: Map<ParcelUuid, ByteArray> = emptyMap()
)
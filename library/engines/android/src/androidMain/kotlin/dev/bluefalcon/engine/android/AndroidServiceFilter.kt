package dev.bluefalcon.engine.android

import android.os.ParcelUuid

/**
 * Android-specific service filter data.
 * Used internally to convert ServiceFilter to Android's ScanFilter format.
 */
internal data class AndroidScanFilter(
    val serviceUuids: List<ParcelUuid> = emptyList(),
    val serviceData: Map<ParcelUuid, ByteArray> = emptyMap()
)

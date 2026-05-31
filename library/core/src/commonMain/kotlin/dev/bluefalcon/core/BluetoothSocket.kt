package dev.bluefalcon.core

import kotlinx.coroutines.flow.SharedFlow

/**
 * Keys for retrieving advertisement data from discovered peripherals
 */
enum class AdvertisementDataRetrievalKeys {
    LocalName,
    ManufacturerData,
    ServiceUUIDsKey,
    IsConnectable
}

/**
 * A bidirectional L2CAP connection-oriented channel.
 *
 * Inbound data is exposed as a [SharedFlow] of byte chunks, mirroring the
 * `characteristicNotifications` pattern used elsewhere in the library so the
 * channel stays idiomatic. Each engine adapts its native stream to this shape;
 * no platform stream types leak into the common API.
 */
interface BluetoothSocket {
    /**
     * Protocol/Service Multiplexer the channel was opened on.
     */
    val psm: Int

    /**
     * The peripheral this channel is connected to.
     */
    val peripheral: BluetoothPeripheral

    /**
     * Whether the channel is currently open. Becomes `false` after [close],
     * on EOF, or on an I/O error.
     */
    val isOpen: Boolean

    /**
     * Inbound frames/chunks read from the channel.
     */
    val incoming: SharedFlow<ByteArray>

    /**
     * Write [data] to the channel. Suspends until the bytes are handed to the
     * platform stream. Throws [L2capException] on I/O failure.
     */
    suspend fun write(data: ByteArray)

    /**
     * Close the channel and release the underlying platform resources.
     */
    fun close()
}

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
     *
     * On the Android and Apple engines this flow is loss-free and bounded (see
     * [L2capInboundBuffer]):
     * - Chunks that arrive before the first collector subscribes are kept and delivered to it.
     * - When the collector is slow, the engine stops reading from the native channel, so L2CAP
     *   flow control pushes back on the remote sender. No chunk is dropped.
     * - After the remote closes the channel, chunks that are already buffered are still delivered.
     *   [close] drops them.
     * - A collector that subscribes after another one receives chunks from that point on.
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

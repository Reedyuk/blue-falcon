package dev.bluefalcon.peripheral

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothSocket
import kotlinx.coroutines.flow.Flow

/**
 * A published L2CAP connection-oriented channel (CoC) server.
 *
 * Create it with [BlueFalconPeripheral.publishL2capChannel] while the peripheral manager is
 * [PeripheralManagerState.Running]. Remote centrals connect to [psm]. The platform assigns the PSM
 * dynamically, so publish it to centrals yourself, usually in a readable GATT characteristic
 * (Apple's convention is the `CBUUIDL2CAPPSMCharacteristicString` characteristic UUID,
 * `ABDD3056-28FA-441D-A470-55A75A52553A`, holding the PSM as a little-endian UInt16).
 *
 * Lifecycle:
 * - [close] unpublishes the channel, closes every socket the listener accepted, and completes
 *   [sockets].
 * - [BlueFalconPeripheral.stop], [BlueFalconPeripheral.close], and a terminal manager failure
 *   close every listener in the same way.
 * - A platform failure of the server (for example Bluetooth turned off) closes the listener and
 *   completes [sockets] with an [dev.bluefalcon.core.L2capException].
 *
 * Accepted sockets are queued in a bounded buffer. When the buffer is full, or the listener
 * already owns its maximum number of open sockets, a new incoming channel is closed immediately
 * instead of being queued without a limit.
 */
interface L2capListener {
    /** Protocol/Service Multiplexer assigned by the platform. */
    val psm: Int

    /** Whether the channel requires an encrypted (paired) link. */
    val secure: Boolean

    /** `false` after [close], manager stop, or a platform failure. */
    val isOpen: Boolean

    /**
     * Incoming channels from remote centrals. This is a single-consumer stream: each socket is
     * delivered to exactly one collector. It completes when the listener closes.
     */
    val sockets: Flow<L2capServerSocket>

    /** Unpublishes the channel and closes all accepted sockets. Idempotent. */
    fun close()
}

/**
 * An L2CAP channel accepted by an [L2capListener].
 *
 * It is the same [BluetoothSocket] contract the central side returns from
 * `BlueFalcon.openL2capChannel`, so transport code can treat both directions alike.
 *
 * On the server side the remote device is a central, not a GATT peripheral. [peripheral]
 * therefore only identifies the remote device: its `uuid` equals [sessionId]`.value` and its
 * GATT fields (`services`, `characteristics`, `name`, `rssi`, `mtuSize`) are empty or `null`.
 */
interface L2capServerSocket : BluetoothSocket {
    /**
     * The remote central, in the same format as [PeripheralSession.id]: the Bluetooth address on
     * Android and the `CBCentral` identifier on Apple platforms. A central may open an L2CAP
     * channel without any GATT activity, so a matching [PeripheralSession] is not guaranteed.
     */
    val sessionId: PeripheralSessionId

    /** Describes the remote central. See [L2capServerSocket]. */
    override val peripheral: BluetoothPeripheral
}

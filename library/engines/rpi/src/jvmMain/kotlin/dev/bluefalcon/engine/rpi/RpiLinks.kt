package dev.bluefalcon.engine.rpi

import com.welie.blessed.ConnectionState
import dev.bluefalcon.core.BluetoothUnknownException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The link of a peripheral, from the connection state that Blessed reports.
 */
internal enum class RpiLinkState {
    Connecting,
    Connected,

    /** Blessed started the disconnect, and BlueZ did not end the link yet. */
    Closing,
    Down;

    val isUp: Boolean
        get() = this == Connecting || this == Connected
}

internal fun ConnectionState.toLinkState(): RpiLinkState = when (this) {
    ConnectionState.CONNECTING -> RpiLinkState.Connecting
    ConnectionState.CONNECTED -> RpiLinkState.Connected
    ConnectionState.DISCONNECTING -> RpiLinkState.Closing
    ConnectionState.DISCONNECTED -> RpiLinkState.Down
}

/**
 * What [RpiLinks.prepareConnect] found.
 */
internal enum class RpiConnectStart {
    /** The connect makes a new link. Give it to Blessed. */
    NewLink,

    /** The peripheral has a link. Blessed ignores the connect. */
    LinkExists,

    /** A disconnect request came during the wait. Do not give the connect to Blessed. */
    Withdrawn,
}

/**
 * Tells if the introspection XML of a BlueZ object lists the child node [node]. The adapter object
 * lists each device object that BlueZ has as a child node, for example `dev_AC_EB_E6_8B_07_3A`.
 */
internal fun bluezHasChildNode(introspectionXml: String, node: String): Boolean =
    Regex("""<node\s+name\s*=\s*(["'])${Regex.escape(node)}\1""").containsMatchIn(introspectionXml)

/**
 * Makes BlueZ ready for a connect, so that a peripheral can connect again after its link ended.
 *
 * Two facts about BlueZ and Blessed (0.65) make this necessary:
 * - A connect needs the BlueZ device object of the peripheral. When the link to a peripheral that
 *   is not bonded ends, Blessed removes that object (`InternalCallback.disconnected` calls
 *   `removeDevice`). BlueZ removes it when a scan found the peripheral and no connect came before
 *   its `TemporaryTimeout` (30 s by default). Without the object, the connect fails in BlueZ at
 *   once and no radio command goes out. Only a scan makes the object again.
 * - BlueZ ends a link 2 s to 3 s after the disconnect request, and Blessed ignores a connect until
 *   then.
 *
 * So [prepareConnect] waits for the end of a link that closes. Then it scans until BlueZ has the
 * device object, when BlueZ does not have it.
 *
 * The record of a link starts at [linkStarts], when the connect goes to Blessed, and it stops at
 * [ended], which the engine calls from the Blessed callbacks `onDisconnectedPeripheral` and
 * `onConnectionFailed`. For a peripheral that is not bonded, Blessed removes the device object
 * before it calls `onDisconnectedPeripheral`. The connection state of Blessed changes before the
 * removal, so the state alone cannot tell that a connect is safe.
 *
 * Limits:
 * - Blessed runs the scan, the connect and the disconnect of all peripherals in one queue, one at
 *   a time. While a connect to a different peripheral holds that queue, the scan and the
 *   disconnect of this peripheral wait, and a time limit of [prepareConnect] can end first. The
 *   stop of the scan for a connect waits in that queue too. If it waits until the scan window of
 *   Blessed ends, Blessed starts the scan again 2 s later, and that scan has no user.
 * - A time limit cannot stop a [hasDevice] call that BlueZ does not answer.
 *
 * @param hasDevice tells if BlueZ has a device object for an address.
 */
internal class RpiLinks(
    private val scans: RpiScans,
    private val linkEndTimeoutMs: Long = LINK_END_TIMEOUT_MS,
    private val discoveryTimeoutMs: Long = DISCOVERY_TIMEOUT_MS,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val hasDevice: suspend (address: String) -> Boolean,
) {
    private enum class Phase { Open, Closing }

    private class Link(val phase: Phase, val reset: () -> Unit)

    private val links = ConcurrentHashMap<String, Link>()

    // The connects that wait in prepareConnect, so that a disconnect request can stop them.
    private val preparations = ConcurrentHashMap<Job, String>()

    // One scan for a connect at a time: RpiScans holds one address, and each scan must end before
    // the scan window of Blessed does (see DISCOVERY_TIMEOUT_MS).
    private val discovery = Mutex()

    /**
     * Call this before a connect to [address] goes to Blessed. [linkState] gives the state that
     * Blessed reports for the peripheral.
     *
     * For [RpiConnectStart.NewLink], the caller must call [linkStarts] and give the connect to
     * Blessed, with no suspension between this call and those two.
     *
     * @throws BluetoothUnknownException when a link that closes does not end, or when BlueZ does
     *   not find the peripheral again. In the two conditions Blessed cannot make the link.
     */
    suspend fun prepareConnect(address: String, linkState: () -> RpiLinkState): RpiConnectStart = coroutineScope {
        val preparation = async(start = CoroutineStart.LAZY) { prepare(address, linkState) }
        preparations[preparation] = address
        try {
            preparation.await()
        } catch (cancellation: CancellationException) {
            // A cancellation of the caller goes on. If not, a disconnect request stopped the wait.
            ensureActive()
            RpiConnectStart.Withdrawn
        } finally {
            preparations.remove(preparation)
        }
    }

    /**
     * Call this when the connect of a new link goes to Blessed, after [prepareConnect] and with no
     * suspension between. A cancellation can then not leave a record that has no link.
     *
     * [reset] removes what the peripheral holds from a link, which is its services. It runs now
     * and when the link ends, so that a caller never sees the services of an earlier link on a new
     * link.
     */
    fun linkStarts(address: String, reset: () -> Unit) {
        reset()
        links[address] = Link(Phase.Open, reset)
    }

    /**
     * Call this for each disconnect request. It stops the connects to [address] that wait in
     * [prepareConnect], because the caller wants no link.
     *
     * Returns false when a disconnect of this link is in Blessed already. Blessed queues a
     * disconnect command for each request that comes while its state is connected, and the state
     * changes only when the first command runs. Blessed then runs a second command after the link
     * ended. That command sets the state of the peripheral to disconnecting, and for a bonded
     * peripheral nothing completes it. So do not give Blessed the request then.
     */
    fun disconnectRequested(address: String, state: RpiLinkState): Boolean {
        preparations.forEach { (preparation, target) -> if (target == address) preparation.cancel() }
        var send = true
        links.computeIfPresent(address) { _, link ->
            when {
                link.phase == Phase.Closing -> link.also { send = false }
                // Blessed ends only a link that is connected. It does nothing for a different state.
                state == RpiLinkState.Connected -> Link(Phase.Closing, link.reset)
                else -> link
            }
        }
        return send
    }

    /** Call this when Blessed reports that the link ended or that the connect failed. */
    fun ended(address: String) {
        links.remove(address)?.reset?.invoke()
    }

    private suspend fun prepare(address: String, linkState: () -> RpiLinkState): RpiConnectStart {
        while (true) {
            awaitLinkEnd(address, linkState)
            if (linkState().isUp) return RpiConnectStart.LinkExists
            ensureDevice(address)
            // A second connect can have made a link during the wait for the scan, and that link
            // can close already. Wait for its end then.
            if (!isEnding(address, linkState())) break
        }
        return if (linkState().isUp) RpiConnectStart.LinkExists else RpiConnectStart.NewLink
    }

    private suspend fun awaitLinkEnd(address: String, linkState: () -> RpiLinkState) {
        val ended = withTimeoutOrNull(linkEndTimeoutMs) {
            while (isEnding(address, linkState())) delay(pollIntervalMs)
            true
        } ?: false
        if (ended) return
        // For a link that is not down, Blessed ignores the connect. The caller must know that no
        // link comes.
        if (linkState() != RpiLinkState.Down) {
            throw BluetoothUnknownException("The link to $address did not end, so a new connect cannot start")
        }
        // The link is down and Blessed reported no end, so the record is old.
        ended(address)
    }

    private fun isEnding(address: String, state: RpiLinkState): Boolean = when (links[address]?.phase) {
        null -> false
        Phase.Closing -> true
        // The link went down and Blessed did not report its end yet. A connect that Blessed did
        // not start yet looks the same, and that one changes to connecting.
        Phase.Open -> !state.isUp
    }

    private suspend fun ensureDevice(address: String) {
        // Most connects stop here, and they do not wait for the scan of a different connect.
        if (hasDevice(address)) return
        discovery.withLock {
            if (hasDevice(address)) return
            scans.startConnectScan(address)
            try {
                val found = withTimeoutOrNull(discoveryTimeoutMs) {
                    while (!hasDevice(address)) delay(pollIntervalMs)
                    true
                } ?: false
                if (!found) {
                    throw BluetoothUnknownException("BlueZ did not find $address again, so the connect cannot start")
                }
            } finally {
                scans.stopConnectScan()
            }
        }
    }

    companion object {
        /** BlueZ ends a link 2 s to 3 s after the disconnect request. */
        const val LINK_END_TIMEOUT_MS = 5_000L

        /**
         * Shorter than the 6 s scan window of Blessed. Blessed stops its scan at the end of a
         * window and starts it again 2 s later, also when a stop request came between. So a scan
         * that is on for longer than a window can start again after its stop. This limit prevents
         * that while the stop does not wait in the Blessed queue.
         */
        const val DISCOVERY_TIMEOUT_MS = 5_000L

        const val POLL_INTERVAL_MS = 50L
    }
}

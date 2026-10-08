package dev.bluefalcon.engine.rpi

import com.welie.blessed.ConnectionState
import dev.bluefalcon.core.BluetoothUnknownException
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
 * What [RpiLinks.connect] did with a connect.
 */
internal enum class RpiConnectStart {
    /** The connect went to Blessed, and it makes a new link. */
    NewLink,

    /** The connect went to Blessed. The peripheral has a link, so Blessed ignores the connect. */
    LinkExists,

    /** A disconnect request came before the connect went to Blessed, so it did not go there. */
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
 * So [connect] waits for the end of a link that closes. Then it scans until BlueZ has the device
 * object, when BlueZ does not have it. Then it gives the connect to Blessed.
 *
 * The record of a link starts when [connect] gives the connect to Blessed, and it stops at
 * [ended], which the engine calls from the Blessed callbacks `onDisconnectedPeripheral` and
 * `onConnectionFailed`. For a peripheral that is not bonded, Blessed removes the device object
 * before it calls `onDisconnectedPeripheral`. The connection state of Blessed changes before the
 * removal, so the state alone cannot tell that a connect is safe.
 *
 * One lock guards the records. The last check of a connect, the record of its link and the call
 * to Blessed are one step under that lock. So a disconnect request, an end report or a second
 * connect to the same peripheral cannot come between the parts of that step.
 *
 * Limits:
 * - Blessed runs the scan, the connect and the disconnect of all peripherals in one queue, one at
 *   a time. While a connect to a different peripheral holds that queue, the scan and the
 *   disconnect of this peripheral wait, and a time limit of [connect] can end first. The stop of
 *   the scan for a connect waits in that queue too. If it waits until the scan window of Blessed
 *   ends, Blessed starts the scan again 2 s later, and that scan has no user.
 * - A time limit cannot stop a [hasDevice] call that BlueZ does not answer.
 * - An end report of Blessed does not identify its link (see [ended]). A second report of an
 *   earlier link can come after the connect of a new link went to Blessed. If Blessed did not
 *   start that connect yet, the report ends the record of the new link. The new link then has no
 *   record: a connect does not wait for its end, and its services stay until the next connect.
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

    private class Link(val reset: () -> Unit) {
        var phase = Phase.Open
    }

    /** A connect to [address] that did not go to Blessed yet. */
    private class Attempt(val address: String) {
        /** A disconnect request came, so the connect must not go to Blessed. */
        var withdrawn = false

        /** The wait of the connect, so that a disconnect request can stop it. */
        var wait: Job? = null
    }

    // Guards the links and the attempts, and the fields of each one.
    private val lock = Any()

    private val links = mutableMapOf<String, Link>()

    private val attempts = mutableSetOf<Attempt>()

    // One scan for a connect at a time: RpiScans holds one address, and each scan must end before
    // the scan window of Blessed does (see DISCOVERY_TIMEOUT_MS).
    private val discovery = Mutex()

    /**
     * Connects to [address]: makes BlueZ ready, and then gives the connect to Blessed with
     * [start]. [linkState] gives the state that Blessed reports for the peripheral.
     *
     * [reset] removes what the peripheral holds from a link, which is its services. It runs before
     * the connect of a new link goes to Blessed, and when that link ends. A caller then never sees
     * the services of an earlier link on a new link.
     *
     * [start] and [reset] run under the lock of this class, so they must not block. No suspension
     * comes after [start], so a cancellation cannot come after the connect went to Blessed.
     *
     * @throws BluetoothUnknownException when a link that closes does not end, or when BlueZ does
     *   not find the peripheral again. In the two conditions Blessed cannot make the link.
     */
    suspend fun connect(
        address: String,
        linkState: () -> RpiLinkState,
        reset: () -> Unit,
        start: () -> Unit,
    ): RpiConnectStart {
        val attempt = Attempt(address)
        synchronized(lock) { attempts += attempt }
        try {
            while (true) {
                if (!awaitReady(attempt, linkState)) return RpiConnectStart.Withdrawn
                handOff(attempt, linkState, reset, start)?.let { return it }
            }
        } finally {
            synchronized(lock) { attempts -= attempt }
        }
    }

    /**
     * Call this for each disconnect request. It stops the connects to [address] that did not go to
     * Blessed yet, because the caller wants no link.
     *
     * Returns false when a disconnect of this link is in Blessed already. Blessed queues a
     * disconnect command for each request that comes while its state is connected, and the state
     * changes only when the first command runs. Blessed then runs a second command after the link
     * ended. That command sets the state of the peripheral to disconnecting, and for a bonded
     * peripheral nothing completes it. So do not give Blessed the request then.
     */
    fun disconnectRequested(address: String, state: RpiLinkState): Boolean {
        val waits = mutableListOf<Job>()
        val send = synchronized(lock) {
            for (attempt in attempts) {
                if (attempt.address != address) continue
                // The stop of the wait is not sufficient, because the wait can be complete
                // already. The connect reads this mark before it goes to Blessed.
                attempt.withdrawn = true
                attempt.wait?.let { waits += it }
            }
            val link = links[address]
            when {
                link == null -> true
                link.phase == Phase.Closing -> false
                // Blessed ends only a link that is connected. It does nothing for a different state.
                state == RpiLinkState.Connected -> {
                    link.phase = Phase.Closing
                    true
                }
                else -> true
            }
        }
        waits.forEach { it.cancel() }
        return send
    }

    /**
     * Call this when Blessed reports that the link ended or that the connect failed. [state] is
     * the state that Blessed reports for the peripheral at that time.
     *
     * Blessed can report the end of one link through two callbacks, and a report does not identify
     * its link. Blessed sets the state to down before it reports an end, and only the connect of a
     * later link sets the state to up again. So a report for a peripheral that is up is the second
     * report of an earlier link. It must not end the link that came after.
     */
    fun ended(address: String, state: RpiLinkState) {
        synchronized(lock) {
            if (!state.isUp) links.remove(address)?.reset?.invoke()
        }
    }

    /**
     * Waits until BlueZ is ready for the connect. Returns false when a disconnect request stopped
     * the wait.
     */
    private suspend fun awaitReady(attempt: Attempt, linkState: () -> RpiLinkState): Boolean = coroutineScope {
        val wait = async(start = CoroutineStart.LAZY) {
            awaitLinkEnd(attempt.address, linkState)
            // A link that is up needs no device check, because Blessed ignores the connect.
            if (!linkState().isUp) ensureDevice(attempt.address)
        }
        val withdrawn = synchronized(lock) {
            attempt.wait = wait
            attempt.withdrawn
        }
        if (withdrawn) wait.cancel()
        try {
            wait.await()
            true
        } catch (cancellation: CancellationException) {
            // A cancellation of the caller goes on. If not, a disconnect request stopped the wait.
            ensureActive()
            false
        }
    }

    /**
     * Gives the connect to Blessed, as one step with the last check and the record of a new link.
     *
     * Returns null when the connect must wait again. A second connect then made a link during the
     * wait, and that link is not up.
     */
    private fun handOff(
        attempt: Attempt,
        linkState: () -> RpiLinkState,
        reset: () -> Unit,
        start: () -> Unit,
    ): RpiConnectStart? = synchronized(lock) {
        if (attempt.withdrawn) return RpiConnectStart.Withdrawn
        val state = linkState()
        if (isEnding(attempt.address, state)) return null
        if (state.isUp) {
            start()
            return RpiConnectStart.LinkExists
        }
        reset()
        links[attempt.address] = Link(reset)
        start()
        RpiConnectStart.NewLink
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
        ended(address, RpiLinkState.Down)
    }

    private fun isEnding(address: String, state: RpiLinkState): Boolean = synchronized(lock) {
        when (links[address]?.phase) {
            null -> false
            Phase.Closing -> true
            // The link went down and Blessed did not report its end yet. A connect that Blessed
            // did not start yet looks the same, and that one changes to connecting.
            Phase.Open -> !state.isUp
        }
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

package dev.bluefalcon.engine.rpi

import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.ConnectionState
import dev.bluefalcon.core.BluetoothUnknownException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [RpiLinks]: a connect must wait for the end of a link that closes, and it must
 * let BlueZ find the peripheral again when BlueZ has no device object for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RpiLinksTest {

    /** The addresses that BlueZ has a device object for. */
    private val devices = mutableSetOf<String>()
    private val scanner = FakeScanner()
    private val scans = RpiScans(scanner)
    private val links = RpiLinks(scans, LINK_END_MS, DISCOVERY_MS, POLL_MS) { address -> address in devices }

    /** The number of times that the services of the board were removed. */
    private var resets = 0

    /** The number of connects to the board that went to Blessed. */
    private var starts = 0

    private val down = { RpiLinkState.Down }
    private val scanAndStop = { address: String -> listOf("address $address", "stop") }

    /** Connects as `RpiEngine.connect()` does. [started] runs when the connect goes to Blessed. */
    private suspend fun connect(
        address: String = BOARD,
        started: () -> Unit = {},
        state: () -> RpiLinkState = down,
    ): RpiConnectStart = links.connect(
        address = address,
        linkState = state,
        reset = { if (address == BOARD) resets++ },
        start = {
            if (address == BOARD) starts++
            started()
        },
    )

    // --- The device object of BlueZ

    @Test
    fun `a connect to a peripheral that BlueZ has starts no scan`() = runTest {
        devices += BOARD

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(emptyList(), scanner.requests)
        assertEquals(0, currentTime)
    }

    @Test
    fun `a connect scans until BlueZ has the peripheral again`() = runTest {
        launch {
            delay(300)
            devices += BOARD
        }

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(scanAndStop(BOARD), scanner.requests)
        assertEquals(300, currentTime)
    }

    @Test
    fun `a connect fails when BlueZ does not find the peripheral`() = runTest {
        assertFailsWith<BluetoothUnknownException> { connect() }

        assertEquals(scanAndStop(BOARD), scanner.requests)
        assertEquals(DISCOVERY_MS, currentTime)
        assertEquals(0, resets)
    }

    @Test
    fun `a connect uses the scan of the application`() = runTest {
        scans.startApplicationScan(emptyList())
        launch {
            delay(300)
            devices += BOARD
        }

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals("services []", scanner.scan)
        assertEquals(listOf("services []"), scanner.requests)
    }

    @Test
    fun `a connect scans on when the application stops its scan during the wait`() = runTest {
        scans.startApplicationScan(emptyList())
        launch {
            delay(100)
            scans.stopApplicationScan()
            assertEquals("address $BOARD", scanner.scan)
            delay(200)
            devices += BOARD
        }

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(null, scanner.scan)
    }

    @Test
    fun `a cancelled connect stops its scan`() = runTest {
        val caller = launch { connect() }
        advanceTimeBy(200)
        assertEquals("address $BOARD", scanner.scan)

        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertEquals(null, scanner.scan)
    }

    @Test
    fun `a connect stops its scan when the device check fails`() = runTest {
        var checks = 0
        val failing = RpiLinks(scans, LINK_END_MS, DISCOVERY_MS, POLL_MS) {
            if (++checks > 3) throw BluetoothUnknownException("no answer") else false
        }

        assertFailsWith<BluetoothUnknownException> { failing.connect(BOARD, down, reset = {}, start = {}) }

        assertEquals(scanAndStop(BOARD), scanner.requests)
    }

    @Test
    fun `two connects scan one after the other`() = runTest {
        launch {
            delay(300)
            devices += BOARD
            delay(300)
            devices += OTHER
        }

        val first = async { connect(BOARD) }
        val second = async { connect(OTHER) }

        assertEquals(listOf(RpiConnectStart.NewLink, RpiConnectStart.NewLink), awaitAll(first, second))
        assertEquals(scanAndStop(BOARD) + scanAndStop(OTHER), scanner.requests)
    }

    @Test
    fun `a connect that waits for the scan of a different connect has its full scan time`() = runTest {
        launch {
            delay(1_500)
            devices += BOARD
        }
        val first = async { connect(BOARD) }
        val second = async { runCatching { connect(OTHER) } }

        assertEquals(RpiConnectStart.NewLink, first.await())
        assertTrue(second.await().exceptionOrNull() is BluetoothUnknownException)
        assertEquals(1_500 + DISCOVERY_MS, currentTime)
    }

    @Test
    fun `a connect that is cancelled while it waits for its turn starts no scan`() = runTest {
        launch {
            delay(300)
            devices += BOARD
        }
        val first = async { connect(BOARD) }
        val second = launch { connect(OTHER) }
        advanceTimeBy(100)

        second.cancelAndJoin()

        assertEquals(RpiConnectStart.NewLink, first.await())
        assertEquals(scanAndStop(BOARD), scanner.requests)
    }

    // --- The end of a link

    @Test
    fun `a connect waits for the end of a link that closes`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        // Blessed reports the link as connected until it starts the disconnect.
        var state = RpiLinkState.Connected
        assertTrue(links.disconnectRequested(BOARD, state))
        val prepared = async { connect { state } }
        advanceTimeBy(1_000)
        state = RpiLinkState.Closing
        advanceTimeBy(1_000)
        assertFalse(prepared.isCompleted)

        // Blessed removes the device object, and then it reports the end of the link.
        state = RpiLinkState.Down
        devices -= BOARD
        links.ended(BOARD, RpiLinkState.Down)
        launch {
            delay(300)
            devices += BOARD
        }

        assertEquals(RpiConnectStart.NewLink, prepared.await())
        assertEquals(scanAndStop(BOARD), scanner.requests)
    }

    @Test
    fun `a connect waits for the end report of a link that went down`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        val prepared = async { connect() }
        advanceTimeBy(1_000)
        assertFalse(prepared.isCompleted)

        links.ended(BOARD, RpiLinkState.Down)

        assertEquals(RpiConnectStart.NewLink, prepared.await())
    }

    @Test
    fun `a cancelled connect stops its wait for the end of a link`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.disconnectRequested(BOARD, RpiLinkState.Connected)
        val caller = launch { connect { RpiLinkState.Closing } }
        advanceTimeBy(1_000)

        caller.cancelAndJoin()

        assertTrue(caller.isCancelled)
        assertEquals(1_000, currentTime)
        assertEquals(emptyList(), scanner.requests)
    }

    @Test
    fun `a connect fails when Blessed does not start the disconnect in time`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.disconnectRequested(BOARD, RpiLinkState.Connected)

        assertFailsWith<BluetoothUnknownException> { connect { RpiLinkState.Connected } }

        assertEquals(LINK_END_MS, currentTime)
    }

    @Test
    fun `a connect fails when BlueZ does not end the link in time`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.disconnectRequested(BOARD, RpiLinkState.Connected)

        assertFailsWith<BluetoothUnknownException> { connect { RpiLinkState.Closing } }

        assertEquals(LINK_END_MS, currentTime)
    }

    @Test
    fun `a connect goes on when the link is down and Blessed reported no end`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.disconnectRequested(BOARD, RpiLinkState.Connected)

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(LINK_END_MS, currentTime)
    }

    @Test
    fun `a connect to an open link prepares nothing`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(RpiConnectStart.LinkExists, connect { RpiLinkState.Connected })

        assertEquals(emptyList(), scanner.requests)
        assertEquals(0, currentTime)
        assertEquals(1, resets)
    }

    @Test
    fun `a connect to a link with no record prepares nothing`() = runTest {
        assertEquals(RpiConnectStart.LinkExists, connect { RpiLinkState.Connected })

        assertEquals(emptyList(), scanner.requests)
        assertEquals(0, currentTime)
        assertEquals(0, resets)
    }

    @Test
    fun `a second connect waits until Blessed starts the first connect`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        var state = RpiLinkState.Down
        launch {
            delay(100)
            state = RpiLinkState.Connecting
        }

        assertEquals(RpiConnectStart.LinkExists, connect { state })

        assertEquals(100, currentTime)
        assertEquals(emptyList(), scanner.requests)
    }

    @Test
    fun `a second connect that waited for its turn prepares nothing when the first made the link`() = runTest {
        launch {
            delay(300)
            devices += BOARD
            delay(300)
            devices += OTHER
        }
        var state = RpiLinkState.Down
        val first = async { connect(started = { state = RpiLinkState.Connected }) { state } }
        val other = async { connect(OTHER) }
        val second = async { connect { state } }

        assertEquals(
            listOf(RpiConnectStart.NewLink, RpiConnectStart.NewLink, RpiConnectStart.LinkExists),
            awaitAll(first, other, second),
        )
        // The second connect did not remove the services of the link that the first one made.
        assertEquals(1, resets)
    }

    @Test
    fun `two connects that completed their wait make one link`() = runTest {
        devices += BOARD
        var state = RpiLinkState.Down
        // The two waits complete before the first connect goes to Blessed. Blessed then makes the
        // link of the first connect, with its services, before the second connect goes on.
        val first = async { connect(started = { state = RpiLinkState.Connected }) { state } }
        val second = async { connect { state } }

        assertEquals(listOf(RpiConnectStart.NewLink, RpiConnectStart.LinkExists), awaitAll(first, second))
        // The second connect did not remove the services of the link that the first one made.
        assertEquals(1, resets)
        assertEquals(0, currentTime)
    }

    @Test
    fun `the second of two connects that completed their wait waits until Blessed starts the first`() = runTest {
        devices += BOARD
        var state = RpiLinkState.Down
        val first = async { connect { state } }
        val second = async { connect { state } }
        launch {
            delay(100)
            state = RpiLinkState.Connecting
        }

        assertEquals(listOf(RpiConnectStart.NewLink, RpiConnectStart.LinkExists), awaitAll(first, second))
        assertEquals(1, resets)
        assertEquals(100, currentTime)
    }

    @Test
    fun `a second connect that waited for its turn waits for the end of a link that went down`() = runTest {
        launch {
            delay(300)
            devices += BOARD
            delay(300)
            devices += OTHER
            // The end report of the link that went down at 400 ms. Blessed removed the device.
            delay(100)
            devices -= BOARD
            links.ended(BOARD, RpiLinkState.Down)
            delay(200)
            devices += BOARD
        }
        var state = RpiLinkState.Down
        val first = async { connect(started = { state = RpiLinkState.Connected }) { state } }
        val other = async { connect(OTHER) }
        val second = async { connect { state } }
        launch {
            delay(400)
            state = RpiLinkState.Down
        }

        assertEquals(List(3) { RpiConnectStart.NewLink }, awaitAll(first, other, second))
        assertEquals(900, currentTime)
    }

    @Test
    fun `a connect that failed leaves no record`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.ended(BOARD, RpiLinkState.Down)

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(0, currentTime)
    }

    // --- The services of a link

    @Test
    fun `the services are removed before a new link and at its end`() = runTest {
        devices += BOARD

        assertEquals(RpiConnectStart.NewLink, connect())
        assertEquals(1, resets)

        links.ended(BOARD, RpiLinkState.Down)
        assertEquals(2, resets)

        // Blessed can report the end of one link through two callbacks.
        links.ended(BOARD, RpiLinkState.Down)
        assertEquals(2, resets)
    }

    @Test
    fun `the second end report of a link does not end the link that came after`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.ended(BOARD, RpiLinkState.Down)
        // A connect comes between the two end reports of the first link, and Blessed starts it.
        assertEquals(RpiConnectStart.NewLink, connect())
        assertEquals(3, resets)

        links.ended(BOARD, RpiLinkState.Connecting)

        // The second link has its record: its services stay, only its first disconnect request
        // goes to Blessed, and its end removes its services.
        assertEquals(3, resets)
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connected))
        assertFalse(links.disconnectRequested(BOARD, RpiLinkState.Connected))
        links.ended(BOARD, RpiLinkState.Down)
        assertEquals(4, resets)
    }

    @Test
    fun `an end report for a link that is connected changes no record`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connected))

        links.ended(BOARD, RpiLinkState.Connected)

        assertEquals(1, resets)
        // The record shows that a disconnect of the link is in Blessed.
        assertFalse(links.disconnectRequested(BOARD, RpiLinkState.Connected))
    }

    // --- A disconnect request

    @Test
    fun `a disconnect request stops a connect that waits for the scan`() = runTest {
        val prepared = async { connect() }
        advanceTimeBy(200)
        assertEquals("address $BOARD", scanner.scan)

        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Down))

        assertEquals(RpiConnectStart.Withdrawn, prepared.await())
        assertEquals(null, scanner.scan)
        assertEquals(200, currentTime)
        assertEquals(0, resets)
        assertEquals(0, starts)
    }

    @Test
    fun `a disconnect request does not stop a later connect`() = runTest {
        val prepared = async { connect() }
        advanceTimeBy(200)
        links.disconnectRequested(BOARD, RpiLinkState.Down)
        assertEquals(RpiConnectStart.Withdrawn, prepared.await())
        devices += BOARD

        assertEquals(RpiConnectStart.NewLink, connect())

        assertEquals(1, starts)
    }

    @Test
    fun `a disconnect request stops a connect that waits for the end of a link`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connected))
        val prepared = async { connect { RpiLinkState.Closing } }
        advanceTimeBy(1_000)

        links.disconnectRequested(BOARD, RpiLinkState.Closing)

        assertEquals(RpiConnectStart.Withdrawn, prepared.await())
        assertEquals(1_000, currentTime)
        assertEquals(1, starts)
    }

    @Test
    fun `a connect that a disconnect request stops at its last step leaves no record`() = runTest {
        // The request comes in the last check of the connect, after its last suspension.
        var requestAtCheck = true
        lateinit var racing: RpiLinks
        racing = RpiLinks(scans, LINK_END_MS, DISCOVERY_MS, POLL_MS) { address ->
            if (requestAtCheck) {
                requestAtCheck = false
                racing.disconnectRequested(address, RpiLinkState.Down)
            }
            true
        }

        var started = false

        assertEquals(RpiConnectStart.Withdrawn, racing.connect(BOARD, down, reset = {}, start = { started = true }))

        assertFalse(started)
        // A record with no link would make this connect wait for the end of that link.
        assertEquals(RpiConnectStart.NewLink, racing.connect(BOARD, down, reset = {}, start = {}))
        assertEquals(0, currentTime)
    }

    @Test
    fun `a disconnect request stops a connect that completed its wait`() = runTest {
        // The request comes after the wait of the connect completed, and before the connect goes
        // on. The device check is the last step of the wait, and the request runs after it.
        var requestAfterCheck = true
        lateinit var racing: RpiLinks
        racing = RpiLinks(scans, LINK_END_MS, DISCOVERY_MS, POLL_MS) { address ->
            if (requestAfterCheck) {
                requestAfterCheck = false
                launch { racing.disconnectRequested(address, RpiLinkState.Down) }
            }
            true
        }
        var started = false

        assertEquals(RpiConnectStart.Withdrawn, racing.connect(BOARD, down, reset = {}, start = { started = true }))

        assertFalse(started)
        assertEquals(RpiConnectStart.NewLink, racing.connect(BOARD, down, reset = {}, start = {}))
        assertEquals(0, currentTime)
    }

    @Test
    fun `a connect that is cancelled at its last step leaves no record`() = runTest {
        var cancelAtCheck = true
        lateinit var caller: Job
        val racing = RpiLinks(scans, LINK_END_MS, DISCOVERY_MS, POLL_MS) {
            if (cancelAtCheck) {
                cancelAtCheck = false
                caller.cancel()
            }
            true
        }
        var started = false
        caller = launch { racing.connect(BOARD, down, reset = {}, start = { started = true }) }
        caller.join()

        assertTrue(caller.isCancelled)
        assertFalse(started)
        assertEquals(RpiConnectStart.NewLink, racing.connect(BOARD, down, reset = {}, start = {}))
        assertEquals(0, currentTime)
    }

    @Test
    fun `a disconnect request does not stop a connect to a different peripheral`() = runTest {
        launch {
            delay(300)
            devices += BOARD
        }
        val prepared = async { connect() }
        advanceTimeBy(100)

        links.disconnectRequested(OTHER, RpiLinkState.Down)

        assertEquals(RpiConnectStart.NewLink, prepared.await())
    }

    @Test
    fun `only the first disconnect request of a link goes to Blessed`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())

        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connected))
        assertFalse(links.disconnectRequested(BOARD, RpiLinkState.Connected))
        assertFalse(links.disconnectRequested(BOARD, RpiLinkState.Closing))

        links.ended(BOARD, RpiLinkState.Down)
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Down))
    }

    @Test
    fun `a disconnect request for a link that is not connected changes no record`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())

        // Blessed does nothing for this request, so the link comes.
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connecting))
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connecting))

        assertEquals(RpiConnectStart.LinkExists, connect { RpiLinkState.Connected })
        assertEquals(0, currentTime)
    }

    @Test
    fun `a disconnect request that comes after the end of the link leaves no record`() = runTest {
        devices += BOARD
        assertEquals(RpiConnectStart.NewLink, connect())
        links.ended(BOARD, RpiLinkState.Down)

        // The caller read the state as connected before the link went down.
        assertTrue(links.disconnectRequested(BOARD, RpiLinkState.Connected))

        assertEquals(RpiConnectStart.NewLink, connect())
        assertEquals(0, currentTime)
    }

    // --- BlueZ and Blessed facts that the code depends on

    @Test
    fun `each Blessed connection state has a link state`() {
        assertEquals(
            listOf(RpiLinkState.Connecting, RpiLinkState.Connected, RpiLinkState.Closing, RpiLinkState.Down),
            listOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTING,
                ConnectionState.DISCONNECTED,
            ).map { it.toLinkState() },
        )
        assertEquals(listOf(true, true, false, false), RpiLinkState.entries.map { it.isUp })
    }

    @Test
    fun `the adapter introspection of BlueZ tells if a device object is there`() {
        // The end of the adapter XML of BlueZ 5.85.
        val xml = """</interface><node name="dev_48_D2_1D_54_E0_DB"/><node name="dev_AC_EB_E6_8B_07_3A"/></node>"""

        assertTrue(bluezHasChildNode(xml, "dev_AC_EB_E6_8B_07_3A"))
        assertFalse(bluezHasChildNode(xml, "dev_AC_EB_E6_8B_07_3B"))
        // A part of a name is not that node.
        assertFalse(bluezHasChildNode(xml, "dev_AC_EB_E6_8B_07"))
        assertTrue(bluezHasChildNode("<node>\n  <node name = 'dev_AC_EB_E6_8B_07_3A' />\n</node>", "dev_AC_EB_E6_8B_07_3A"))
    }

    @Test
    fun `the scan for a connect ends before the scan window of Blessed does`() {
        val scanWindow = BluetoothCentralManager::class.java.getDeclaredField("SCAN_WINDOW")
            .apply { isAccessible = true }
            .getLong(null)

        assertTrue(RpiLinks.DISCOVERY_TIMEOUT_MS < scanWindow)
    }

    private companion object {
        const val BOARD = "AC:EB:E6:8B:07:3A"
        const val OTHER = "AC:EB:E6:8B:07:3B"

        // The time values of these tests. They are not the values of the engine.
        const val LINK_END_MS = 3_000L
        const val DISCOVERY_MS = 2_000L
        const val POLL_MS = 50L
    }
}

package dev.bluefalcon.engine.rpi

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [RpiScans]: the application and a connect share the one scan of Blessed, and a
 * request of one must not stop or replace the scan that the other needs.
 */
class RpiScansTest {

    private val scanner = FakeScanner()
    private val scans = RpiScans(scanner)

    @Test
    fun `the application scan starts and stops the scan`() {
        scans.startApplicationScan(listOf(SERVICE))
        assertTrue(scans.applicationScanActive)
        assertEquals("services [$SERVICE]", scanner.scan)

        scans.stopApplicationScan()
        assertFalse(scans.applicationScanActive)
        assertEquals(null, scanner.scan)
    }

    @Test
    fun `a connect scan starts and stops the scan when the application does not scan`() {
        scans.startConnectScan(BOARD)
        assertEquals("address $BOARD", scanner.scan)
        assertFalse(scans.applicationScanActive)

        scans.stopConnectScan()
        assertEquals(null, scanner.scan)
    }

    @Test
    fun `a connect scan does not change the scan of the application`() {
        scans.startApplicationScan(emptyList())

        scans.startConnectScan(BOARD)
        scans.stopConnectScan()

        assertEquals("services []", scanner.scan)
        assertEquals(listOf("services []"), scanner.requests)
    }

    @Test
    fun `a scan that the application starts during a connect scan stays on`() {
        scans.startConnectScan(BOARD)

        scans.startApplicationScan(listOf(SERVICE))
        scans.stopConnectScan()

        assertEquals("services [$SERVICE]", scanner.scan)
    }

    @Test
    fun `the connect scan starts again when the application stops its scan`() {
        scans.startConnectScan(BOARD)
        scans.startApplicationScan(emptyList())

        scans.stopApplicationScan()
        assertEquals("address $BOARD", scanner.scan)

        scans.stopConnectScan()
        assertEquals(null, scanner.scan)
    }

    @Test
    fun `a stop request of the application with no scan of its own does not touch the connect scan`() {
        scans.startConnectScan(BOARD)

        scans.stopApplicationScan()

        assertEquals("address $BOARD", scanner.scan)
        assertEquals(listOf("address $BOARD"), scanner.requests)
    }

    @Test
    fun `requests from many threads leave one consistent scan`() {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val workers = List(8) { worker ->
            thread {
                start.await()
                runCatching {
                    repeat(500) { step ->
                        when ((worker + step) % 4) {
                            0 -> scans.startApplicationScan(emptyList())
                            1 -> scans.startConnectScan(BOARD)
                            2 -> scans.stopApplicationScan()
                            else -> scans.stopConnectScan()
                        }
                    }
                }.onFailure(failures::add)
            }
        }
        start.countDown()
        workers.forEach { it.join() }

        assertEquals(emptyList(), failures)
        // The scan that is on agrees with the users that want one.
        if (scans.applicationScanActive) {
            assertEquals("services []", scanner.scan)
        } else {
            assertTrue(scanner.scan == null || scanner.scan == "address $BOARD")
        }

        scans.startApplicationScan(listOf(SERVICE))
        scans.startConnectScan(BOARD)
        assertEquals("services [$SERVICE]", scanner.scan)

        scans.stopApplicationScan()
        assertEquals("address $BOARD", scanner.scan)

        scans.stopConnectScan()
        assertEquals(null, scanner.scan)
    }

    private companion object {
        const val BOARD = "AC:EB:E6:8B:07:3A"
        val SERVICE: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    }
}

/** Blessed has one scan: a new scan request replaces the scan that is on. */
internal class FakeScanner : RpiScanner {
    /** The scan that is on, or null. */
    var scan: String? = null
        private set

    val requests = mutableListOf<String>()

    override fun scanForServices(serviceUuids: List<UUID>) = request("services $serviceUuids")

    override fun scanForAddress(address: String) = request("address $address")

    override fun stop() {
        scan = null
        requests += "stop"
    }

    private fun request(scan: String) {
        this.scan = scan
        requests += scan
    }
}

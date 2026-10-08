package dev.bluefalcon.engine.rpi

import java.util.UUID

/**
 * The scan calls of Blessed that [RpiScans] uses.
 */
internal interface RpiScanner {
    /** Starts a scan for peripherals that advertise one of [serviceUuids], or for all when it is empty. */
    fun scanForServices(serviceUuids: List<UUID>)

    /** Starts a scan for the peripheral with [address]. */
    fun scanForAddress(address: String)

    fun stop()
}

/**
 * Shares the one scan of Blessed between the application and a connect of the engine.
 *
 * A connect can need a scan of its own, so that BlueZ finds its peripheral again (see [RpiLinks]).
 * Blessed has one scan: a new scan request replaces the scan that is on, and a stop request stops
 * it. So each request goes through this class, which knows both users:
 * - The scan of the application has priority. Blessed applies its service filter and its address
 *   filter to the results that it gives, not to the discovery of BlueZ. So BlueZ makes a device
 *   object for each peripheral that it hears in a scan, and a connect needs no scan of its own
 *   while the application scans. (Blessed gives BlueZ one limit for each scan: an RSSI of
 *   -80 dBm or more.)
 * - When the application stops its scan and a connect still needs one, the scan for the connect
 *   starts.
 *
 * All changes are made under one lock, so a request of one user cannot come between the decision
 * and the action of the other user.
 */
internal class RpiScans(private val scanner: RpiScanner) {
    private val lock = Any()

    // Written under the lock. The Blessed callback thread reads it for each scan result.
    @Volatile
    private var applicationFilter: List<UUID>? = null

    private var connectAddress: String? = null

    /** True while a scan that the application started is on. */
    val applicationScanActive: Boolean
        get() = applicationFilter != null

    fun startApplicationScan(serviceUuids: List<UUID>) = synchronized(lock) {
        applicationFilter = serviceUuids
        scanner.scanForServices(serviceUuids)
    }

    fun stopApplicationScan() = synchronized(lock) {
        val address = connectAddress
        when {
            address == null -> scanner.stop()
            // The scan for the connect is on already. A new request would stop it for a moment.
            applicationFilter == null -> Unit
            else -> scanner.scanForAddress(address)
        }
        applicationFilter = null
    }

    /** Asks for a scan that lets BlueZ find [address]. Only one connect can ask at a time. */
    fun startConnectScan(address: String) = synchronized(lock) {
        connectAddress = address
        if (applicationFilter == null) scanner.scanForAddress(address)
    }

    fun stopConnectScan() = synchronized(lock) {
        connectAddress = null
        if (applicationFilter == null) scanner.stop()
    }
}

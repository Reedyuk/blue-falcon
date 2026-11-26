package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.Logger
import dev.bluefalcon.core.ServiceFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanOptionsKey
import platform.CoreBluetooth.CBCentralManagerRestoredStateScanServicesKey
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCentralManagerScanOptionSolicitedServiceUUIDsKey
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSNumber

/**
 * Configuration of the Apple central engine ([AppleEngine], `IosEngine`).
 *
 * @property restorationIdentifier Core Bluetooth state-restoration identifier for the engine's
 * scanning `CBCentralManager` (`CBCentralManagerOptionRestoreIdentifierKey`). When it is set and
 * iOS relaunches the app in the background, the scan that was running is reported once through
 * [AppleEngine.restoredScan] and the engine reports [AppleEngine.isScanning] as `true`.
 *
 * What is restored and what is not:
 * - **Scans only.** Connections and pending connects are **not** restored. Since #298 every
 *   connection uses its own per-peer `CBCentralManager`, and those managers have no restoration
 *   identifier. After a relaunch, scan again and call `connect` again.
 * - iOS does **not** relaunch or restore an app that the user force-quit from the app switcher.
 *   Restoration only applies when the system ended the app (for example for memory).
 * - The app must declare `bluetooth-central` in `UIBackgroundModes` in its `Info.plist`. Without
 *   it, Core Bluetooth throws `NSInternalInconsistencyException` when the manager is created.
 * - Use the same identifier on every launch, and create the engine early in
 *   `application(_:didFinishLaunchingWithOptions:)`, so Core Bluetooth can hand the state back.
 * - macOS does not support Core Bluetooth state restoration. The identifier has no effect there.
 *
 * `null` (the default) keeps the previous behaviour: no restoration.
 */
class AppleEngineConfiguration(
    val restorationIdentifier: String? = null,
) {
    init {
        require(restorationIdentifier == null || restorationIdentifier.isNotBlank()) {
            "Restoration identifier must not be blank"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is AppleEngineConfiguration && other.restorationIdentifier == restorationIdentifier

    override fun hashCode(): Int = restorationIdentifier.hashCode()

    override fun toString(): String = "AppleEngineConfiguration(restorationIdentifier=$restorationIdentifier)"
}

/**
 * A scan that Core Bluetooth restored in `centralManager(_:willRestoreState:)`.
 *
 * Core Bluetooth keeps this scan running after the relaunch, so discoveries arrive in
 * `peripherals` without a new `scan` call. To change it, call `scan` or `stopScanning` as usual.
 *
 * @property filters the service filters the scan was started with. Empty means all services.
 * @property allowDuplicates the restored `CBCentralManagerScanOptionAllowDuplicatesKey` value.
 * @property solicitedServices the restored `CBCentralManagerScanOptionSolicitedServiceUUIDsKey` value.
 */
class AppleRestoredScan internal constructor(
    val filters: List<ServiceFilter>,
    val allowDuplicates: Boolean,
    val solicitedServices: List<ServiceFilter>,
) {
    override fun equals(other: Any?): Boolean = other is AppleRestoredScan &&
        other.filters == filters &&
        other.allowDuplicates == allowDuplicates &&
        other.solicitedServices == solicitedServices

    override fun hashCode(): Int = (filters.hashCode() * 31 + allowDuplicates.hashCode()) * 31 + solicitedServices.hashCode()

    override fun toString(): String =
        "AppleRestoredScan(filters=$filters, allowDuplicates=$allowDuplicates, solicitedServices=$solicitedServices)"
}

/** Parsed `willRestoreState` dictionary of the scanning manager. */
internal class AppleCentralRestoredState(
    val scan: AppleRestoredScan?,
    /** Peripherals Core Bluetooth handed back. The engine does not adopt them (scan-only restoration). */
    val ignoredPeripheralCount: Int,
)

/**
 * Adopts the scanning manager's restored state once per engine (#325, scan-only restoration).
 * Thread-safe: Core Bluetooth callbacks and app calls can race.
 */
internal class AppleScanRestoration(private val logger: Logger?) {
    private val _restoredScan = MutableStateFlow<AppleRestoredScan?>(null)
    val restoredScan: StateFlow<AppleRestoredScan?> = _restoredScan.asStateFlow()
    private val adopted = MutableStateFlow(false)
    private val appControlsScan = MutableStateFlow(false)

    /** The app called scan or stopScanning. A later restoration must not override its choice. */
    fun appChangedScan() {
        appControlsScan.value = true
    }

    /**
     * Publishes the restored scan. [reportScanning] runs first when the engine must report that it
     * is scanning, so a collector of [restoredScan] already sees `isScanning == true`.
     * Returns `true` when [reportScanning] ran.
     */
    fun adopt(state: AppleCentralRestoredState?, reportScanning: () -> Unit = {}): Boolean {
        if (state == null || !adopted.compareAndSet(expect = false, update = true)) return false
        if (state.ignoredPeripheralCount > 0) {
            logger?.warn(
                "Core Bluetooth restored ${state.ignoredPeripheralCount} peripheral(s) on the scanning manager; " +
                    "Blue Falcon restores scans only, so connect to them again",
            )
        }
        val scan = state.scan ?: return false
        val scanning = !appControlsScan.value
        if (scanning) reportScanning()
        _restoredScan.value = scan
        logger?.info("Core Bluetooth restored a scan: $scan")
        return scanning
    }
}

internal fun parseCentralRestoredState(state: Map<Any?, *>): AppleCentralRestoredState {
    val services = state[CBCentralManagerRestoredStateScanServicesKey] as? List<*>
    val options = state[CBCentralManagerRestoredStateScanOptionsKey] as? Map<*, *>
    val scan = if (services == null && options == null) null else AppleRestoredScan(
        filters = services.toServiceFilters(),
        allowDuplicates = options?.get(CBCentralManagerScanOptionAllowDuplicatesKey).asBoolean(),
        solicitedServices = (options?.get(CBCentralManagerScanOptionSolicitedServiceUUIDsKey) as? List<*>)
            .toServiceFilters(),
    )
    val peripherals = (state[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>)?.size ?: 0
    return AppleCentralRestoredState(scan, peripherals)
}

private fun List<*>?.toServiceFilters(): List<ServiceFilter> =
    orEmpty().filterIsInstance<CBUUID>().map { ServiceFilter(it.toUuid()) }

private fun Any?.asBoolean(): Boolean = when (this) {
    is Boolean -> this
    is NSNumber -> boolValue
    is Number -> toInt() != 0
    else -> false
}

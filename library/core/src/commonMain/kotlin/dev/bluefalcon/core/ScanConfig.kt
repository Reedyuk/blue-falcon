package dev.bluefalcon.core

import kotlin.time.Duration

/**
 * Optional scan settings for [BlueFalcon.scan]. The defaults are the settings Blue Falcon used
 * before this type existed, so `ScanConfig()` does not change behavior.
 *
 * Use it for duty cycling, for example [ScanMode.LowLatency] in the foreground and
 * [ScanMode.LowPower] in the background.
 *
 * Platform support:
 *
 * | Field | Android | iOS / macOS | Other engines |
 * |---|---|---|---|
 * | [mode] | `ScanSettings.setScanMode` | Ignored: Core Bluetooth controls the duty cycle | Ignored |
 * | [reportDelay] | `ScanSettings.setReportDelay` (batch scan) | Ignored | Ignored |
 * | [allowDuplicates] | Ignored: Android always reports every advertisement | `CBCentralManagerScanOptionAllowDuplicatesKey` | Ignored |
 *
 * @property mode Scan duty cycle. Default [ScanMode.LowLatency].
 * @property reportDelay Android only. When positive, results are delivered in batches after this
 *   delay. The device must support offloaded batching
 *   (`BluetoothAdapter.isOffloadedScanBatchingSupported`); otherwise Android rejects the scan.
 *   Default [Duration.ZERO] (immediate delivery).
 * @property allowDuplicates Apple only. Whether every advertisement of a device is reported, which
 *   keeps RSSI and advertisement data fresh at the cost of power. Default `true`. iOS ignores this
 *   in the background.
 */
data class ScanConfig(
    val mode: ScanMode = ScanMode.LowLatency,
    val reportDelay: Duration = Duration.ZERO,
    val allowDuplicates: Boolean = true,
) {
    init {
        require(reportDelay >= Duration.ZERO && reportDelay.isFinite()) {
            "Scan report delay must be zero or positive and finite"
        }
    }
}

/** Scan duty cycle, mapped to `ScanSettings.SCAN_MODE_*` on Android. */
enum class ScanMode {
    /** `SCAN_MODE_OPPORTUNISTIC`: only receives results from scans started by other apps. */
    Opportunistic,

    /** `SCAN_MODE_LOW_POWER`: lowest power; the recommended background mode. */
    LowPower,

    /** `SCAN_MODE_BALANCED`. */
    Balanced,

    /** `SCAN_MODE_LOW_LATENCY`: highest duty cycle; the previous hardcoded value. */
    LowLatency,
}

package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Releases dropped Objective-C references soon instead of at the next regular GC.
 *
 * Kotlin/Native releases an Objective-C object only when its garbage collector frees the last
 * Kotlin reference to it. Core Bluetooth closes a `CBL2CAPChannel` over the air only after the
 * channel object is deallocated (#334). Without collections, a closed channel stayed open on the
 * air until the periodic GC, about 10 s later on device.
 *
 * One collection is not enough. On device, two collections within 200 ms after close still left
 * the channel open, and a schedule that keeps collecting for about 0.8 s closed it on the air about
 * 0.55–0.65 s after `close()`. So the releaser runs one short schedule per burst of requests. A
 * request that arrives while a schedule runs adds one more schedule.
 */
@OptIn(NativeRuntimeApi::class)
internal class AppleNativeReleaser(
    private val scope: CoroutineScope,
    /** Delay before each collection, counted from the previous one. */
    private val collectionDelaysMillis: List<Long> = listOf(50, 100, 150, 200, 300),
    private val collect: () -> Unit = { GC.collect() },
) {
    private val running = MutableStateFlow(false)
    private val requested = MutableStateFlow(false)

    fun requestRelease() {
        requested.value = true
        if (!running.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                while (requested.compareAndSet(expect = true, update = false)) {
                    for (delayMillis in collectionDelaysMillis) {
                        delay(delayMillis)
                        collect()
                    }
                }
            } finally {
                running.value = false
            }
            // A request that came between the last check and `running = false`.
            if (requested.value) requestRelease()
        }
    }
}

/**
 * Remembers L2CAP channels this engine closed recently, per peripheral and PSM.
 *
 * Core Bluetooth needs about half a second after the channel object is released to close the
 * channel over the air. Until then, opening the same PSM on the same peripheral fails with
 * "L2CAP PSM already connected" (#334).
 */
internal class AppleRecentChannelCloses(
    private val window: Duration = 2.seconds,
    private val clock: TimeSource = TimeSource.Monotonic,
) {
    private val closes = MutableStateFlow<Map<Pair<String, Int>, TimeMark>>(emptyMap())

    fun closed(peripheralId: String, psm: Int) {
        closes.update { current ->
            current.filterValues { it.elapsedNow() < window } + ((peripheralId to psm) to clock.markNow())
        }
    }

    /** Time left until this engine's own last close on [psm] should have finished, or `null`. */
    fun remaining(peripheralId: String, psm: Int): Duration? {
        val closedAt = closes.value[peripheralId to psm] ?: return null
        return (window - closedAt.elapsedNow()).takeIf { it.isPositive() }
    }
}

/** Core Bluetooth rejected an open (`peripheral(_:didOpen:error:)` with an error). */
internal class AppleL2capOpenRejected(val failure: L2capException) : Exception(failure.message, failure)

/**
 * Runs [attempt]. When Core Bluetooth rejects it while this engine's own previous channel on the
 * same PSM is still closing ([remaining] is positive), retries every [retryInterval] until that
 * window ends. Then the original [L2capException] is thrown, as without the retry.
 */
internal suspend fun <T> openWhileOwnCloseSettles(
    remaining: () -> Duration?,
    retryInterval: Duration = 100.milliseconds,
    onRetry: () -> Unit = {},
    attempt: suspend () -> T,
): T {
    while (true) {
        try {
            return attempt()
        } catch (rejected: AppleL2capOpenRejected) {
            val left = remaining() ?: throw rejected.failure
            onRetry()
            delay(minOf(retryInterval, left))
        }
    }
}

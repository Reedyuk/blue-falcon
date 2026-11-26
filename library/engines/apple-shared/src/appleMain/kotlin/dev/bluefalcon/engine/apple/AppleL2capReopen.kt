package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Remembers L2CAP channels this engine closed recently, per peripheral and PSM.
 *
 * Even when the channel object is released at once (see [AppleL2capHandle]) and the remote sees
 * end-of-stream within about 0.1 s, Core Bluetooth rejects an immediate reopen of the same PSM
 * with "L2CAP PSM already connected" for roughly 0.3–0.4 s on device (#334).
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

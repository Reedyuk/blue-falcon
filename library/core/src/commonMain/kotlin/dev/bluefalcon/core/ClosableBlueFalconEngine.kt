package dev.bluefalcon.core

/**
 * Optional terminal lifecycle for an engine whose final owner can release it.
 *
 * [close] is idempotent. Concurrent callers await the same cleanup. When it completes
 * successfully, no engine-owned worker, scan, native connection, receiver, socket,
 * watchdog, operation waiter or callback may remain active or mutate engine state.
 * New work must be rejected once closing starts. Cancelling a caller waiting for close
 * must not abandon cleanup; failures must remain observable to later close callers.
 *
 * This capability is additive: externally shared engines still implement [BlueFalconEngine].
 */
interface ClosableBlueFalconEngine : BlueFalconEngine {
    suspend fun close()
}

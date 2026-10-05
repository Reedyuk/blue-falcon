package dev.bluefalcon.peripheral.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

/** One reusable timer per peer, including canceled owners awaiting dispatcher cleanup. */
internal class PeripheralInactivityWatchdog private constructor(
    private val revision: MutableStateFlow<Long>,
    private val job: Job,
) {
    fun refresh(token: Long) { revision.value = token }
    fun cancel() = job.cancel()
    val isActive: Boolean get() = job.isActive
    companion object {
        fun start(scope: CoroutineScope, timeout: Duration, token: Long, admission: PeripheralRequestAdmission, onExpired: (Long) -> Unit): PeripheralInactivityWatchdog? {
            val permit = admission.acquire(0, false) ?: return null
            val revision = MutableStateFlow(token)
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (true) {
                        val observed = revision.value
                        val refreshed = withTimeoutOrNull(timeout) { revision.first { it != observed } }
                        if (refreshed == null) {
                            onExpired(observed)
                            // A stale expiry waits for refresh; an accepted expiry cancels
                            // this exact watchdog when the manager retires the peer.
                            revision.first { it != observed }
                        }
                    }
                } finally { permit.delivered() }
            }
            return PeripheralInactivityWatchdog(revision, job)
        }
    }
}

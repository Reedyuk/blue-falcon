package dev.bluefalcon.engine.android

import kotlinx.coroutines.Job

/** Terminal admission fence and native resources owned by one Android engine. */
internal class AndroidEngineLifecycle(val job: Job) {
    val lock = Any()
    private var closed = false
    private var cleanupFailure: CleanupFailure? = null
    private val resources = java.util.IdentityHashMap<Any, () -> Unit>()
    val isClosed: Boolean get() = synchronized(lock) { closed }

    fun <T> withOpen(action: () -> T): T? = synchronized(lock) {
        if (closed) null else action()
    }

    fun retain(owner: Any, close: () -> Unit): Boolean = synchronized(lock) {
        if (closed) {
            try { close() } catch (failure: Throwable) { recordCleanupFailure(failure) }
            false
        } else {
            resources[owner] = close
            true
        }
    }

    fun release(owner: Any) = synchronized(lock) { resources.remove(owner); Unit }

    fun destroy() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        val closers = resources.values.toList()
        resources.clear()
        try {
            closers.forEach { close ->
                try { close() } catch (failure: Throwable) {
                    recordCleanupFailure(failure)
                }
            }
        } finally {
            job.cancel()
        }
    }

    fun recordCleanupFailure(failure: Throwable) = synchronized(lock) {
        val report = cleanupFailure
        if (report == null) {
            cleanupFailure = CleanupFailure(failure)
        } else {
            // Native owner close may be observed again by the engine closer. Deduplicate
            // only against retained samples, keeping diagnostic memory constant.
            if (report.cause === failure || report.suppressed.any { it === failure }) return@synchronized
            if (report.failureCount < Long.MAX_VALUE) report.failureCount++
            if (report.suppressed.size < MAX_FAILURE_SAMPLES) report.addSuppressed(failure)
        }
    }

    private class CleanupFailure(first: Throwable) : IllegalStateException(first) {
        @Volatile var failureCount = 1L
        override val message: String get() =
            "Android engine native teardown failed ($failureCount errors; first plus at most $MAX_FAILURE_SAMPLES samples retained)"
    }

    private companion object { const val MAX_FAILURE_SAMPLES = 16 }

    suspend fun close() {
        destroy()
        job.join()
        synchronized(lock) { cleanupFailure }?.let { throw it }
    }
}

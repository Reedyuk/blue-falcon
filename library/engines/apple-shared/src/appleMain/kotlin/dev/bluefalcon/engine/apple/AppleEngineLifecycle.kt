package dev.bluefalcon.engine.apple

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Final-owner admission and cleanup, independent of engine/caller cancellation. */
internal class AppleEngineLifecycle(
    private val scope: CoroutineScope,
    private val cleanup: suspend () -> Unit,
) {
    private data class Admission(val closing: Boolean = false, val nativeCalls: Int = 0)
    private val admission = MutableStateFlow(Admission())
    private val cleanupFailure = MutableStateFlow<Throwable?>(null)
    fun rememberCleanupFailure(cause: Throwable?) { if (cause != null) cleanupFailure.compareAndSet(null, cause) }
    fun <T> trackNativeCleanup(action: () -> T): T = try { action() } catch (cause: Throwable) {
        rememberCleanupFailure(cause)
        throw cause
    }
    private val completion = MutableStateFlow<Deferred<Unit>?>(null)
    private val cleanupScope = object : CoroutineScope {
        override val coroutineContext = scope.coroutineContext.minusKey(Job)
    }
    val isOpen: Boolean get() = !admission.value.closing && scope.coroutineContext[Job]?.isActive != false
    init {
        // An ordinary Job completion hook waits for children, which may need native close
        // to unblock. This small child terminates immediately when the public scope cancels.
        scope.launch { awaitCancellation() }.invokeOnCompletion { requestClose() }
    }
    private fun acquire(): Boolean {
        while (true) {
            val current = admission.value
            if (current.closing || scope.coroutineContext[Job]?.isActive == false) return false
            if (admission.compareAndSet(current, current.copy(nativeCalls = current.nativeCalls + 1))) return true
        }
    }
    private fun release() { admission.update { it.copy(nativeCalls = it.nativeCalls - 1) } }
    fun <T> native(action: () -> T): T {
        check(acquire()) { "Apple engine is closing or closed" }
        return try { action() } finally { release() }
    }
    fun callback(action: () -> Unit) {
        if (!acquire()) return
        try { action() } finally { release() }
    }
    suspend fun <T> operation(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(isOpen) { "Apple engine is closing or closed" }
        val task = scope.async {
            check(isOpen) { "Apple engine is closing or closed" }
            val result = action()
            currentCoroutineContext().ensureActive()
            result
        }
        return try { task.await() } finally { task.cancel() }
    }
    fun requestClose(): Deferred<Unit> {
        admission.update { it.copy(closing = true) }
        completion.value?.let { return it }
        val pending = cleanupScope.async(start = CoroutineStart.LAZY) {
            scope.coroutineContext[Job]?.cancel()
            try {
                admission.first { it.nativeCalls == 0 }
                try { cleanup() } catch (cause: Throwable) { rememberCleanupFailure(cause) }
            } finally { scope.coroutineContext[Job]?.join() }
            cleanupFailure.value?.let { throw it }
            Unit
        }
        if (completion.compareAndSet(null, pending)) { pending.start(); return pending }
        pending.cancel()
        return checkNotNull(completion.value)
    }
    suspend fun close() {
        requestClose().await()
        // A rejected late native channel may fail cleanup after the shared close completed.
        cleanupFailure.value?.let { throw it }
    }
}

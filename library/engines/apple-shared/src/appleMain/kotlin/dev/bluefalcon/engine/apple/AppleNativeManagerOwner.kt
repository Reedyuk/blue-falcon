package dev.bluefalcon.engine.apple

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import platform.Foundation.NSRecursiveLock

/** One terminal native manager close; delegates and powered-on waiters share its fence. */
internal class AppleNativeManagerOwner {
    private val closed = MutableStateFlow(false)
    private val lock = NSRecursiveLock()
    private var done = false
    private var failure: Throwable? = null
    val isOpen: Boolean get() = !closed.value
    suspend fun <T> awaitReady(state: StateFlow<T>, ready: (T) -> Boolean) {
        combine(state, closed) { value, closing ->
            check(!closing) { "Apple native manager is closed" }
            value
        }.first(ready)
    }
    fun forward(action: () -> Unit) {
        if (closed.value) return
        lock.lock()
        try { if (!closed.value) action() } finally { lock.unlock() }
    }
    fun close(actions: List<() -> Unit>) {
        closed.value = true
        lock.lock()
        try {
            if (!done) {
                done = true
                actions.forEach { action ->
                    try { action() } catch (cause: Throwable) {
                        if (failure == null) failure = cause else failure!!.addSuppressed(cause)
                    }
                }
            }
            failure?.let { throw it }
        } finally { lock.unlock() }
    }
}

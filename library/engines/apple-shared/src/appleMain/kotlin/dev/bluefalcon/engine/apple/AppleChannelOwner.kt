package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.L2capException
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSLock

/** Teardown ownership of every pending open and delivered-but-unwrapped native channel.
 * Correlation remains the engine callback path responsibility. */
internal class AppleChannelOwner<T : Any> {
    private val lock = NSLock()
    private var closed = false
    private val waiters = mutableSetOf<CompletableDeferred<T>>()
    private val channels = mutableSetOf<T>()
    private val delivered = mutableMapOf<CompletableDeferred<T>, T>()
    private inline fun <R> locked(action: () -> R): R {
        lock.lock()
        return try { action() } finally { lock.unlock() }
    }
    fun registerWaiter(waiter: CompletableDeferred<T>) = locked {
        check(!closed) { "Apple channel owner is closed" }
        waiters.add(waiter)
        Unit
    }
    fun forgetWaiter(waiter: CompletableDeferred<T>) = locked { waiters.remove(waiter); Unit }
    fun retain(channel: T): Boolean = locked { if (closed) false else { channels.add(channel); true } }
    fun associate(waiter: CompletableDeferred<T>, channel: T): Boolean = locked {
        if (closed || channel !in channels || waiter !in waiters) false
        else { delivered[waiter] = channel; true }
    }
    fun claim(waiter: CompletableDeferred<T>, channel: T): Boolean = locked {
        if (delivered[waiter] !== channel) false
        else { delivered.remove(waiter); true }
    }
    fun reclaim(waiter: CompletableDeferred<T>): T? = locked {
        delivered.remove(waiter)?.also { channels.remove(it) }
    }
    fun release(channel: T) = locked { channels.remove(channel); Unit }
    fun close(closeChannel: (T) -> Unit) {
        val retained = locked {
            if (closed) return
            closed = true
            val pending = waiters.toList()
            waiters.clear()
            delivered.clear()
            val native = channels.toList()
            channels.clear()
            pending to native
        }
        retained.first.forEach { it.completeExceptionally(L2capException("Apple engine closed before channel open completed")) }
        var failure: Throwable? = null
        retained.second.forEach { channel ->
            try { closeChannel(channel) } catch (cause: Throwable) {
                if (failure == null) failure = cause
            }
        }
        failure?.let { throw it }
    }
}

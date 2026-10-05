package dev.bluefalcon.engine.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AndroidOwnedResource<T : Any>(
    val native: T,
    private val lifetime: AndroidEngineLifecycle,
    private val closeNative: (T) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                closeNative(native)
            } catch (failure: Throwable) {
                lifetime.recordCleanupFailure(failure)
                throw failure
            } finally { lifetime.release(this) }
        }
    }
}

/** Register before blocking native connect, so cancellation/engine close can abort it. */
internal suspend fun <T : Any> openAndroidOwnedResource(
    lifetime: AndroidEngineLifecycle,
    scope: CoroutineScope,
    create: () -> T,
    connect: (T) -> Unit,
    close: (T) -> Unit,
): AndroidOwnedResource<T> {
    val pending = AtomicReference<AndroidOwnedResource<T>?>(null)
    val opening = scope.async(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val owner = lifetime.withOpen {
            AndroidOwnedResource(create(), lifetime, close).also {
                pending.set(it)
                lifetime.retain(it, it::close)
            }
        } ?: throw IllegalStateException("AndroidEngine is closed")
        try {
            currentCoroutineContext().ensureActive()
            connect(owner.native)
            currentCoroutineContext().ensureActive()
            check(!lifetime.isClosed) { "AndroidEngine is closed" }
            owner
        } catch (failure: Throwable) {
            runCatching { owner.close() }
            throw failure
        }
    }
    return try {
        opening.await()
    } catch (cancelled: CancellationException) {
        opening.cancel()
        pending.get()?.let { runCatching { it.close() } }
        throw cancelled
    }
}

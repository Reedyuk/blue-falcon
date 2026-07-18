package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.GattResponseHandle
import dev.bluefalcon.peripheral.GattResponseResult
import dev.bluefalcon.peripheral.GattResponseStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultGattResponseHandle(
    private val responder: (GattResponseStatus, ByteArray?) -> Unit,
) : GattResponseHandle {

    private val mutex = Mutex()
    private val terminal = CompletableDeferred<Unit>()
    private var state = State.Pending

    override suspend fun respond(
        status: GattResponseStatus,
        value: ByteArray?,
    ): GattResponseResult {
        val copiedValue = value?.copyOf()
        val result = mutex.withLock {
            when (state) {
                State.Pending -> {
                    state = State.Responded
                    GattResponseResult.Responded
                }

                State.Responded -> GattResponseResult.AlreadyResponded
                State.Expired -> GattResponseResult.Expired
            }
        }

        if (result == GattResponseResult.Responded) {
            terminal.complete(Unit)
            responder(status, copiedValue)
        }

        return result
    }

    internal suspend fun expire(
        fallbackStatus: GattResponseStatus? = null,
    ): Boolean {
        val expired = mutex.withLock {
            if (state != State.Pending) {
                false
            } else {
                state = State.Expired
                true
            }
        }

        if (expired && fallbackStatus != null) {
            terminal.complete(Unit)
            responder(fallbackStatus, null)
        } else if (expired) {
            terminal.complete(Unit)
        }

        return expired
    }

    internal fun tryExpire(fallbackStatus: GattResponseStatus? = null): Boolean {
        if (!mutex.tryLock()) return false
        val expired = try {
            if (state != State.Pending) {
                false
            } else {
                state = State.Expired
                true
            }
        } finally {
            mutex.unlock()
        }

        if (expired) {
            terminal.complete(Unit)
            if (fallbackStatus != null) responder(fallbackStatus, null)
        }

        return expired
    }

    internal suspend fun awaitTerminal() {
        terminal.await()
    }

    internal suspend fun isPending(): Boolean = mutex.withLock {
        state == State.Pending
    }

    private enum class State {
        Pending,
        Responded,
        Expired,
    }
}

package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select

/** Private ingress counts include the callback currently executing. Ordinary ingress rejects newest; owned rejection retires its peer. */
data class AppleCallbackIngressStatus(
    val retainedCallbacks: Int = 0,
    val retainedPayloadBytes: Int = 0,
    val retainedTerminalCallbacks: Int = 0,
    val rejectedCallbacks: Long = 0,
    val closed: Boolean = false,
)

internal fun snapshotCallbackPayload(value: ByteArray?): ByteArray? = value?.copyOf()

internal class AppleNativeConnectionToken<T : Any>(val peripheralUuid: String, val owner: T, val origin: Any? = null) {
    val terminated = CompletableDeferred<Unit>()
    internal val acceptingCallbacks = MutableStateFlow(true)
    internal val operationOwner = MutableStateFlow<AppleCentralConnectionKey?>(null)
}

/** A delegate keeps this binding even if the same native object is reconnected. */
internal class AppleNativeConnectionCallbacks<T : Any>(
    private val token: AppleNativeConnectionToken<T>,
    private val ownership: AppleNativeConnectionOwnership<T>,
) {
    fun forward(callback: (AppleNativeConnectionToken<T>) -> Unit) {
        if (ownership.isActive(token)) callback(token)
    }
}

internal class AppleNativeConnectionOwnership<T : Any> {
    private val owners = MutableStateFlow<Map<String, AppleNativeConnectionToken<T>>>(emptyMap())

    fun connected(peripheralUuid: String, owner: T, origin: Any? = null): AppleNativeConnectionToken<T> {
        val token = AppleNativeConnectionToken(peripheralUuid, owner, origin)
        while (true) {
            val current = owners.value
            if (owners.compareAndSet(current, current + (peripheralUuid to token))) {
                return token
            }
        }
    }

    fun disconnected(peripheralUuid: String, owner: T): Boolean {
        val token = capture(peripheralUuid, owner) ?: return false
        return disconnected(token)
    }

    fun disconnected(token: AppleNativeConnectionToken<T>): Boolean {
        while (true) {
            val current = owners.value
            if (current[token.peripheralUuid] !== token) return false
            if (owners.compareAndSet(current, current - token.peripheralUuid)) return true
        }
    }

    fun isActive(peripheralUuid: String, owner: T): Boolean =
        capture(peripheralUuid, owner) != null

    fun isActive(token: AppleNativeConnectionToken<T>): Boolean =
        owners.value[token.peripheralUuid] === token && token.acceptingCallbacks.value

    fun beginRetirement(token: AppleNativeConnectionToken<T>): Boolean =
        owners.value[token.peripheralUuid] === token && token.acceptingCallbacks.compareAndSet(true, false)

    fun snapshot(): List<AppleNativeConnectionToken<T>> = owners.value.values.toList()

    fun current(peripheralUuid: String): AppleNativeConnectionToken<T>? =
        owners.value[peripheralUuid]

    fun capture(peripheralUuid: String, owner: T): AppleNativeConnectionToken<T>? =
        current(peripheralUuid)?.takeIf { it.owner === owner && it.acceptingCallbacks.value }
}

internal class AppleCentralCallbackDispatcher(
    scope: CoroutineScope,
    private val maximumCallbacks: Int = 256,
    private val maximumPayloadBytes: Int = 1_048_576,
    private val maximumTerminalCallbacks: Int = 64,
) {
    init {
        require(maximumCallbacks > 0)
        require(maximumPayloadBytes >= 0)
        require(maximumTerminalCallbacks > 0)
    }

    private val _status = MutableStateFlow(AppleCallbackIngressStatus())
    val status: StateFlow<AppleCallbackIngressStatus> = _status.asStateFlow()
    private class Callback(val payloadBytes: Int, val terminal: Boolean, val action: suspend () -> Unit)
    private val callbacks = Channel<Callback>(
        capacity = maximumCallbacks,
        onUndeliveredElement = { release(it) },
    )
    // Peer admission is bounded at 32 by ApplePeerManagerEpochs and remains reserved
    // until retirement runs. One cleanup per epoch plus the active worker fits 64 slots.
    // This lane carries no payload and cannot be consumed by notification/discovery traffic.
    private val terminals = Channel<Callback>(
        capacity = maximumTerminalCallbacks,
        onUndeliveredElement = { release(it) },
    )
    private val worker = scope.launch {
        while (true) {
            val callback = terminals.tryReceive().getOrNull() ?: select<Callback?> {
                terminals.onReceiveCatching { it.getOrNull() }
                callbacks.onReceiveCatching { it.getOrNull() }
            } ?: break
            try {
                callback.action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // One malformed platform event must not stop delivery of later BLE callbacks.
            } finally {
                release(callback)
            }
        }
    }
    init {
        // Also runs when a cancelled parent prevents the worker from ever starting.
        worker.invokeOnCompletion {
            _status.update { it.copy(closed = true) }
            callbacks.cancel()
            terminals.cancel()
        }
    }

    /** Reject newest on item/byte exhaustion; never block the native callback thread. */
    fun dispatchTerminal(callback: suspend () -> Unit): Boolean = admit(0, true, callback)

    fun dispatch(payloadBytes: Int = 0, callback: suspend () -> Unit): Boolean {
        require(payloadBytes >= 0)
        return admit(payloadBytes, false, callback)
    }

    fun <T : Any> dispatchOwned(
        token: AppleNativeConnectionToken<T>,
        ownership: AppleNativeConnectionOwnership<T>,
        payloadBytes: Int = 0,
        onRejected: (AppleNativeConnectionToken<T>) -> Unit,
        callback: suspend () -> Unit,
    ): Boolean {
        if (!ownership.isActive(token)) return false
        val admitted = dispatch(payloadBytes) {
            if (ownership.isActive(token)) callback()
        }
        if (!admitted && ownership.isActive(token)) onRejected(token)
        return admitted
    }

    private fun admit(payloadBytes: Int, terminal: Boolean, action: suspend () -> Unit): Boolean {
        while (true) {
            val current = _status.value
            val exhausted = if (terminal) current.retainedTerminalCallbacks >= maximumTerminalCallbacks
                else current.retainedCallbacks - current.retainedTerminalCallbacks >= maximumCallbacks
            if (current.closed || exhausted ||
                payloadBytes > maximumPayloadBytes - current.retainedPayloadBytes
            ) {
                reject()
                return false
            }
            if (_status.compareAndSet(current, current.copy(
                    retainedCallbacks = current.retainedCallbacks + 1,
                    retainedPayloadBytes = current.retainedPayloadBytes + payloadBytes,
                    retainedTerminalCallbacks = current.retainedTerminalCallbacks + if (terminal) 1 else 0,
                ))) break
        }
        val callback = Callback(payloadBytes, terminal, action)
        if ((if (terminal) terminals else callbacks).trySend(callback).isSuccess) return true
        release(callback)
        reject()
        return false
    }

    suspend fun close() {
        _status.update { it.copy(closed = true) }
        callbacks.cancel()
        terminals.cancel()
        worker.cancelAndJoin()
    }

    private fun release(callback: Callback) {
        _status.update { it.copy(
            retainedCallbacks = it.retainedCallbacks - 1,
            retainedPayloadBytes = it.retainedPayloadBytes - callback.payloadBytes,
            retainedTerminalCallbacks = it.retainedTerminalCallbacks - if (callback.terminal) 1 else 0,
        ) }
    }

    private fun reject() {
        _status.update { it.copy(rejectedCallbacks =
            if (it.rejectedCallbacks == Long.MAX_VALUE) Long.MAX_VALUE else it.rejectedCallbacks + 1) }
    }
}

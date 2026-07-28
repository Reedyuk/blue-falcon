package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteCapability
import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteReady
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface AppleCentralWritePeer {
    val peripheralUuid: String
    val connected: Boolean
    val canSendWithoutResponse: Boolean

    fun maximumWriteValueLength(writeType: CharacteristicWriteType): Int
}

internal interface AppleCentralWriteTarget : AppleCentralWritePeer {
    val characteristicUuid: String

    fun writeValue(
        payload: ByteArray,
        writeType: CharacteristicWriteType,
    )
}

internal class AppleCentralWriteController(
    scope: CoroutineScope,
    internal val registry: AppleCentralOperationRegistry = AppleCentralOperationRegistry(),
) {
    private val mutex = Mutex()
    private val connections = mutableMapOf<String, AppleCentralConnectionKey>()

    private val _capabilities =
        MutableStateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>>(emptyMap())
    val capabilities: StateFlow<Map<CharacteristicWriteKey, CharacteristicWriteCapability>> =
        _capabilities.asStateFlow()

    private val _ready = MutableSharedFlow<CharacteristicWriteReady>(extraBufferCapacity = 64)
    val ready: SharedFlow<CharacteristicWriteReady> = _ready.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            registry.readyEdges.collect { connection ->
                val active = mutex.withLock {
                    connections[connection.peripheralUuid] == connection
                }
                if (active) {
                    _ready.tryEmit(
                        CharacteristicWriteReady(
                            CharacteristicWriteKey(
                                connection.peripheralUuid,
                                CharacteristicWriteType.WithoutResponse,
                            )
                        )
                    )
                }
            }
        }
    }

    suspend fun connected(peer: AppleCentralWritePeer): AppleCentralConnectionKey {
        val maximumWithResponse =
            peer.maximumWriteValueLength(CharacteristicWriteType.WithResponse)
        val maximumWithoutResponse =
            peer.maximumWriteValueLength(CharacteristicWriteType.WithoutResponse)
        val connection = registry.connected(peer.peripheralUuid)
        mutex.withLock {
            connections[peer.peripheralUuid] = connection
            _capabilities.value = _capabilities.value
                .filterKeys { it.peripheralUuid != peer.peripheralUuid } +
                mapOf(
                    CharacteristicWriteKey(
                        peer.peripheralUuid,
                        CharacteristicWriteType.WithResponse,
                    ) to CharacteristicWriteCapability(
                        maximumLength = maximumWithResponse,
                        ready = true,
                        supported = true,
                    ),
                    CharacteristicWriteKey(
                        peer.peripheralUuid,
                        CharacteristicWriteType.WithoutResponse,
                    ) to CharacteristicWriteCapability(
                        maximumLength = maximumWithoutResponse,
                        ready = peer.canSendWithoutResponse,
                        supported = true,
                    ),
                )
        }
        return connection
    }

    suspend fun disconnected(peripheralUuid: String): Boolean {
        val connection = mutex.withLock {
            val removed = connections.remove(peripheralUuid) ?: return false
            _capabilities.value =
                _capabilities.value.filterKeys { it.peripheralUuid != peripheralUuid }
            removed
        }
        return registry.disconnect(connection)
    }

    suspend fun write(
        target: AppleCentralWriteTarget,
        value: ByteArray,
        writeType: CharacteristicWriteType,
    ): CharacteristicWriteResult {
        if (!target.connected) return CharacteristicWriteResult.Disconnected
        val connection = currentConnection(target.peripheralUuid)
            ?: return CharacteristicWriteResult.Disconnected
        val capability = _capabilities.value[
            CharacteristicWriteKey(target.peripheralUuid, writeType)
        ] ?: return CharacteristicWriteResult.Disconnected
        if (!capability.supported) return CharacteristicWriteResult.Unsupported
        capability.maximumLength?.let { maximumLength ->
            if (value.size > maximumLength) {
                return CharacteristicWriteResult.PayloadTooLarge(maximumLength)
            }
        }

        val payload = value.copyOf()
        return when (writeType) {
            CharacteristicWriteType.WithoutResponse ->
                writeWithoutResponse(connection, target, payload)
            CharacteristicWriteType.WithResponse ->
                writeWithResponse(connection, target, payload)
        }
    }

    suspend fun onReadyToSendWithoutResponse(peer: AppleCentralWritePeer): Boolean {
        val connection = currentConnection(peer.peripheralUuid) ?: return false
        updateCapabilityReady(
            peer.peripheralUuid,
            CharacteristicWriteType.WithoutResponse,
            peer.canSendWithoutResponse,
        )
        return registry.updateReadiness(connection, peer.canSendWithoutResponse)
    }

    suspend fun onCharacteristicWritten(
        peripheralUuid: String,
        characteristicUuid: String,
        failure: Throwable?,
    ): Boolean {
        val connection = currentConnection(peripheralUuid) ?: return false
        val key = AppleCentralOperationKey(
            peripheralUuid = peripheralUuid,
            generation = connection.generation,
            characteristicUuid = characteristicUuid,
        )
        val completed = registry.completeWrite(
            key,
            failure?.let(CharacteristicWriteResult::Failed)
                ?: CharacteristicWriteResult.Sent,
        )
        if (completed) {
            updateCapabilityReady(
                peripheralUuid,
                CharacteristicWriteType.WithResponse,
                ready = true,
            )
        }
        return completed
    }

    internal suspend fun currentConnection(
        peripheralUuid: String,
    ): AppleCentralConnectionKey? = mutex.withLock {
        connections[peripheralUuid]
    }

    private suspend fun writeWithoutResponse(
        connection: AppleCentralConnectionKey,
        target: AppleCentralWriteTarget,
        payload: ByteArray,
    ): CharacteristicWriteResult {
        if (!target.canSendWithoutResponse) {
            updateCapabilityReady(
                target.peripheralUuid,
                CharacteristicWriteType.WithoutResponse,
                ready = false,
            )
            registry.updateReadiness(connection, ready = false)
            return CharacteristicWriteResult.Backpressured
        }
        return try {
            target.writeValue(payload, CharacteristicWriteType.WithoutResponse)
            CharacteristicWriteResult.Sent
        } catch (failure: Throwable) {
            CharacteristicWriteResult.Failed(failure)
        }
    }

    private suspend fun writeWithResponse(
        connection: AppleCentralConnectionKey,
        target: AppleCentralWriteTarget,
        payload: ByteArray,
    ): CharacteristicWriteResult {
        val key = AppleCentralOperationKey(
            peripheralUuid = target.peripheralUuid,
            generation = connection.generation,
            characteristicUuid = target.characteristicUuid,
        )
        val result = CompletableDeferred<CharacteristicWriteResult>()
        if (!registry.registerWrite(key, result::complete)) {
            return CharacteristicWriteResult.Backpressured
        }
        updateCapabilityReady(
            target.peripheralUuid,
            CharacteristicWriteType.WithResponse,
            ready = false,
        )
        try {
            target.writeValue(payload, CharacteristicWriteType.WithResponse)
        } catch (failure: Throwable) {
            registry.completeWrite(key, CharacteristicWriteResult.Failed(failure))
            updateCapabilityReady(
                target.peripheralUuid,
                CharacteristicWriteType.WithResponse,
                ready = true,
            )
        }

        return try {
            result.await()
        } catch (cancellation: CancellationException) {
            registry.abandonWrite(key)
            throw cancellation
        }
    }

    private suspend fun updateCapabilityReady(
        peripheralUuid: String,
        writeType: CharacteristicWriteType,
        ready: Boolean,
    ) {
        mutex.withLock {
            val key = CharacteristicWriteKey(peripheralUuid, writeType)
            val existing = _capabilities.value[key] ?: return
            _capabilities.value = _capabilities.value + (key to existing.copy(ready = ready))
        }
    }
}

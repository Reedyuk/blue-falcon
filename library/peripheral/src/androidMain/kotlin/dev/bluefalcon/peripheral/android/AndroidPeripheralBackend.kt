package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.Logger
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.PeripheralUnsupportedException
import dev.bluefalcon.peripheral.internal.PeripheralBackend
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class AndroidPeripheralBackend(
    private val stack: AndroidBluetoothStack,
    private val logger: Logger?,
    private val operationTimeout: Duration = 10.seconds,
) : PeripheralBackend {
    private val lock = Any()
    private var state: BackendState = BackendState.Stopped
    private var generation = 0L
    private var eventSink: PeripheralBackendEventSink? = null

    private val platformSupported =
        stack.capabilities.localGattServer && stack.capabilities.connectableAdvertising

    override val capabilities: PeripheralCapabilities = PeripheralCapabilities(
        localGattServer = stack.capabilities.localGattServer,
        connectableAdvertising = stack.capabilities.connectableAdvertising,
        multiCentral = platformSupported,
        targetedNotifications = platformSupported,
        notificationReadiness = platformSupported,
        maximumUpdateValueLength = platformSupported,
        forcedDisconnect = platformSupported,
        connectionLifecycleVisibility = platformSupported,
        preparedWrites = platformSupported,
        stateRestoration = false,
    )

    init {
        require(operationTimeout > Duration.ZERO && operationTimeout.isFinite()) {
            "Android peripheral operation timeout must be positive and finite"
        }
    }

    override suspend fun start(
        config: PeripheralConfig,
        eventSink: PeripheralBackendEventSink,
    ) {
        validateCapabilities()
        val startGeneration = synchronized(lock) {
            when (state) {
                BackendState.Stopped -> Unit
                BackendState.Closed -> throw PeripheralLifecycleException(
                    "Android peripheral backend is closed",
                )
                else -> throw PeripheralLifecycleException(
                    "Android peripheral backend is not stopped",
                )
            }
            val allocatedGeneration = ++generation
            state = BackendState.Starting(allocatedGeneration)
            this.eventSink = eventSink
            allocatedGeneration
        }
        val listener = AndroidBluetoothStackListener { event ->
            onStackEvent(startGeneration, event)
        }

        try {
            stack.open(listener)
            config.advertiseConfig.services.forEach { service ->
                withTimeout(operationTimeout) {
                    stack.addService(service)
                }
            }
            withTimeout(operationTimeout) {
                stack.startAdvertising(config.advertiseConfig)
            }

            val published = synchronized(lock) {
                if (state == BackendState.Starting(startGeneration)) {
                    state = BackendState.Running(startGeneration)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                throw PeripheralLifecycleException(
                    "Android peripheral start was superseded by shutdown",
                )
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable) {
                rollbackStart(startGeneration)
            }
            throw cause
        }
    }

    override suspend fun stop() {
        shutdown(terminal = false)
    }

    override suspend fun close() {
        shutdown(terminal = true)
    }

    override suspend fun notify(
        sessionId: PeripheralSessionId,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = NotificationResult.Unsupported

    override suspend fun disconnect(sessionId: PeripheralSessionId): DisconnectResult =
        DisconnectResult.Unsupported

    private fun validateCapabilities() {
        if (!stack.capabilities.localGattServer) {
            throw PeripheralUnsupportedException("Android local GATT server")
        }
        if (!stack.capabilities.connectableAdvertising) {
            throw PeripheralUnsupportedException("Android connectable advertising")
        }
    }

    private suspend fun rollbackStart(startGeneration: Long) {
        val shutdown = synchronized(lock) {
            when (val current = state) {
                is BackendState.Starting ->
                    if (current.generation == startGeneration) {
                        createShutdownLocked(startGeneration, terminal = false)
                    } else {
                        null
                    }

                is BackendState.Running ->
                    if (current.generation == startGeneration) {
                        createShutdownLocked(startGeneration, terminal = false)
                    } else {
                        null
                    }

                is BackendState.ShuttingDown ->
                    current.takeIf { it.generation == startGeneration }

                BackendState.Stopped,
                BackendState.Closed,
                -> null
            }
        } ?: return

        performOrAwaitShutdown(shutdown)
    }

    private suspend fun shutdown(terminal: Boolean) {
        val shutdown = synchronized(lock) {
            when (val current = state) {
                BackendState.Stopped -> {
                    if (terminal) {
                        state = BackendState.Closed
                        eventSink = null
                    }
                    null
                }

                BackendState.Closed -> null

                is BackendState.Starting ->
                    createShutdownLocked(current.generation, terminal)

                is BackendState.Running ->
                    createShutdownLocked(current.generation, terminal)

                is BackendState.ShuttingDown -> {
                    if (terminal && !current.terminal) {
                        current.copy(terminal = true).also { state = it }
                    } else {
                        current
                    }
                }
            }
        } ?: return

        performOrAwaitShutdown(shutdown)
    }

    private fun createShutdownLocked(
        shutdownGeneration: Long,
        terminal: Boolean,
    ): BackendState.ShuttingDown {
        eventSink = null
        return BackendState.ShuttingDown(
            generation = shutdownGeneration,
            terminal = terminal,
            completion = CompletableDeferred(),
            ownerClaimed = false,
        ).also { state = it }
    }

    private suspend fun performOrAwaitShutdown(shutdown: BackendState.ShuttingDown) {
        val owner = synchronized(lock) {
            val current = state as? BackendState.ShuttingDown
            if (current == null || current.completion !== shutdown.completion) {
                false
            } else if (!current.ownerClaimed) {
                state = current.copy(ownerClaimed = true)
                true
            } else {
                false
            }
        }

        if (!owner) {
            shutdown.completion.await()
            return
        }

        teardownPlatform()
        val terminal = synchronized(lock) {
            val current = state as? BackendState.ShuttingDown
            if (current != null && current.completion === shutdown.completion) {
                state = if (current.terminal) BackendState.Closed else BackendState.Stopped
                current.terminal
            } else {
                false
            }
        }
        shutdown.completion.complete(Unit)
        if (terminal) {
            synchronized(lock) { eventSink = null }
        }
    }

    private fun teardownPlatform() {
        runCatching { stack.stopAdvertising() }
            .onFailure { logger?.warn("Failed to stop Android peripheral advertising", it) }
        runCatching { stack.closeGattServer() }
            .onFailure { logger?.warn("Failed to close Android peripheral GATT server", it) }
    }

    private fun onStackEvent(eventGeneration: Long, event: AndroidGattEvent) {
        val sink = synchronized(lock) {
            if (state == BackendState.Running(eventGeneration)) eventSink else null
        } ?: return
        handleEvent(event, sink)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleEvent(
        event: AndroidGattEvent,
        sink: PeripheralBackendEventSink,
    ) = Unit

    private sealed interface BackendState {
        data object Stopped : BackendState
        data class Starting(val generation: Long) : BackendState
        data class Running(val generation: Long) : BackendState
        data class ShuttingDown(
            val generation: Long,
            val terminal: Boolean,
            val completion: CompletableDeferred<Unit>,
            val ownerClaimed: Boolean,
        ) : BackendState
        data object Closed : BackendState
    }
}

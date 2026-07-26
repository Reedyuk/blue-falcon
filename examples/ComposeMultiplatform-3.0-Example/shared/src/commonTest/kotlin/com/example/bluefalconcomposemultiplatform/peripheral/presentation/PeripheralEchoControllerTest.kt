package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.EchoGatt
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWrite
import dev.bluefalcon.peripheral.GattCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattDescriptorReadRequest
import dev.bluefalcon.peripheral.GattDescriptorWriteRequest
import dev.bluefalcon.peripheral.GattExecuteWriteRequest
import dev.bluefalcon.peripheral.GattResponseHandle
import dev.bluefalcon.peripheral.GattResponseResult
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationReadinessState
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralEvent
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralPluginRegistry
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.SessionState
import dev.bluefalcon.plugins.queue.PeripheralQueue
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class PeripheralEchoControllerTest {

    @Test
    fun unsupportedRuntimeDisablesActions() = runTest {
        val controller = PeripheralEchoController(
            runtime = null,
            scope = backgroundScope,
        )

        assertFalse(controller.state.value.supported)
        assertFalse(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
        assertFalse(controller.state.value.canSend)
    }

    @Test
    fun missingRequiredCapabilityDisablesActionsAndGuardsDirectCalls() = runTest {
        val capabilityVariants = listOf(
            SUPPORTED_TEST_CAPABILITIES.copy(localGattServer = false),
            SUPPORTED_TEST_CAPABILITIES.copy(connectableAdvertising = false),
        )

        capabilityVariants.forEach { capabilities ->
            val manager = FakePeripheral(capabilities)
            val queue = FakeQueue()
            val controller = PeripheralEchoController(
                runtime = PeripheralExampleRuntime(manager, queue),
                scope = backgroundScope,
            )

            assertFalse(controller.state.value.supported)
            assertFalse(controller.state.value.canStart)
            assertFalse(controller.state.value.canStop)
            assertFalse(controller.state.value.canSend)

            manager.mutableState.value = PeripheralManagerState.Running
            manager.mutableSessions.value = setOf(
                FakeSession(
                    initialSubscriptions = setOf(EchoGatt.characteristicId),
                ),
            )
            runCurrent()

            assertFalse(controller.state.value.canStart)
            assertFalse(controller.state.value.canStop)
            assertFalse(controller.state.value.canSend)

            controller.start()
            controller.sendNotification()
            controller.stop()

            assertTrue(manager.startConfigs.isEmpty())
            assertEquals(0, manager.stopCalls)
            assertTrue(queue.sendCalls.isEmpty())
        }
    }

    @Test
    fun supportedCapabilitiesEnableLifecycleActions() = runTest {
        val manager = FakePeripheral(SUPPORTED_TEST_CAPABILITIES)
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        assertTrue(controller.state.value.supported)
        assertTrue(controller.state.value.canStart)

        controller.start()
        runCurrent()

        assertFalse(controller.state.value.canStart)
        assertTrue(controller.state.value.canStop)
    }

    @Test
    fun startUsesEchoConfigAndStopCallsManager() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        controller.start()

        val firstConfig = assertNotNull(manager.startConfigs.singleOrNull())
        assertEquals("Blue Falcon Echo", firstConfig.advertiseConfig.localName)
        assertEquals(
            listOf(EchoGatt.serviceUuid),
            firstConfig.advertiseConfig.serviceUuids,
        )
        assertEquals(EchoGatt.restorationIdentifier, firstConfig.restorationIdentifier)

        val service = firstConfig.advertiseConfig.services.single()
        assertEquals(EchoGatt.serviceUuid, service.uuid)

        val characteristic = service.characteristics.single()
        assertEquals(EchoGatt.characteristicUuid, characteristic.uuid)
        assertEquals(
            setOf(
                CharacteristicProperty.READ,
                CharacteristicProperty.WRITE,
                CharacteristicProperty.WRITE_NO_RESPONSE,
                CharacteristicProperty.NOTIFY,
                CharacteristicProperty.INDICATE,
            ),
            characteristic.properties,
        )
        assertContentEquals(
            "Hello from Blue Falcon".encodeToByteArray(),
            characteristic.initialValue,
        )

        controller.stop()
        controller.start()

        assertEquals(1, manager.stopCalls)
        assertEquals(2, manager.startConfigs.size)
        assertSame(firstConfig, manager.startConfigs.last())
    }

    @Test
    fun lifecycleFailuresAreCaughtAndLogStaysBounded() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.startFailure = IllegalStateException("start unavailable")
        controller.start()

        assertEquals(
            listOf("Start failed: start unavailable"),
            controller.state.value.log,
        )

        manager.stopFailure = IllegalStateException("stop unavailable")
        repeat(101) {
            controller.stop()
        }

        assertEquals(100, controller.state.value.log.size)
        assertTrue(
            controller.state.value.log.all { message ->
                message == "Stop failed: stop unavailable"
            },
        )
    }

    @Test
    fun startFailureRemainsStoppableAndRecoversToStopped() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        manager.mutableSessions.value = setOf(
            FakeSession(initialSubscriptions = setOf(EchoGatt.characteristicId)),
        )
        runCurrent()
        val failure = IllegalStateException("start unavailable")

        manager.startFailure = failure
        controller.start()
        runCurrent()

        val failedState = assertIs<PeripheralManagerState.Failed>(
            controller.state.value.managerState,
        )
        assertSame(failure, failedState.cause)
        assertEquals(
            listOf("Start failed: start unavailable"),
            controller.state.value.log,
        )
        assertTrue(controller.state.value.canStop)
        assertFalse(controller.state.value.canStart)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        manager.startFailure = null
        controller.stop()
        runCurrent()

        assertEquals(PeripheralManagerState.Stopped, controller.state.value.managerState)
        assertTrue(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
    }

    @Test
    fun stopFailureRemainsStoppableAndRetryRecovers() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        manager.mutableSessions.value = setOf(
            FakeSession(initialSubscriptions = setOf(EchoGatt.characteristicId)),
        )
        controller.start()
        runCurrent()
        assertTrue(controller.state.value.canSend)
        val failure = IllegalStateException("stop unavailable")

        manager.stopFailure = failure
        controller.stop()
        runCurrent()

        val failedState = assertIs<PeripheralManagerState.Failed>(
            controller.state.value.managerState,
        )
        assertSame(failure, failedState.cause)
        assertEquals(
            listOf("Stop failed: stop unavailable"),
            controller.state.value.log,
        )
        assertTrue(controller.state.value.canStop)
        assertFalse(controller.state.value.canStart)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        manager.stopFailure = null
        controller.stop()
        runCurrent()

        assertEquals(PeripheralManagerState.Stopped, controller.state.value.managerState)
        assertTrue(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
    }

    @Test
    fun startErrorPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        val startError = AssertionError("fatal start")
        manager.startFailure = startError

        val thrownStartError = assertFailsWith<AssertionError> {
            controller.start()
        }

        assertSame(startError, thrownStartError)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun stopErrorPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        controller.start()
        val stopError = AssertionError("fatal stop")
        manager.stopFailure = stopError

        val thrownStopError = assertFailsWith<AssertionError> {
            controller.stop()
        }

        assertSame(stopError, thrownStopError)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun startCancellationPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        val cancellation = CancellationException("cancel start")
        manager.startFailure = cancellation

        val thrownCancellation = assertFailsWith<CancellationException> {
            controller.start()
        }

        assertSame(cancellation, thrownCancellation)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun stopCancellationPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        controller.start()
        val cancellation = CancellationException("cancel stop")
        manager.stopFailure = cancellation

        val thrownCancellation = assertFailsWith<CancellationException> {
            controller.stop()
        }

        assertSame(cancellation, thrownCancellation)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun sessionsAndManagerStateRemainReactive() = runTest {
        val manager = FakePeripheral()
        val session = FakeSession(
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.mutableState.value = PeripheralManagerState.Running
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        assertEquals(PeripheralManagerState.Running, controller.state.value.managerState)
        assertEquals(1, controller.state.value.sessionCount)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertTrue(controller.state.value.canStop)
        assertTrue(controller.state.value.canSend)

        session.mutableSubscriptions.value = emptySet()
        runCurrent()

        assertEquals(1, controller.state.value.sessionCount)
        assertEquals(0, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        session.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertTrue(controller.state.value.canSend)
    }

    @Test
    fun replacingSessionsStopsObservingRemovedSubscriptions() = runTest {
        val manager = FakePeripheral()
        val removed = FakeSession(
            id = PeripheralSessionId("removed"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val retained = FakeSession(
            id = PeripheralSessionId("retained"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val replacement = FakeSession(
            id = PeripheralSessionId("replacement"),
        )
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.mutableSessions.value = setOf(removed, retained)
        runCurrent()

        assertEquals(2, controller.state.value.sessionCount)
        assertEquals(2, controller.state.value.subscribedSessionCount)

        manager.mutableSessions.value = setOf(retained, replacement)
        runCurrent()

        assertEquals(2, controller.state.value.sessionCount)
        assertEquals(1, controller.state.value.subscribedSessionCount)

        removed.mutableSubscriptions.value = emptySet()
        runCurrent()
        removed.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(1, controller.state.value.subscribedSessionCount)

        replacement.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(2, controller.state.value.subscribedSessionCount)
    }

    @Test
    fun sendTargetsOnlySessionsSubscribedToEchoCharacteristic() = runTest {
        val subscribed = FakeSession(
            id = PeripheralSessionId("subscribed"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val unrelated = FakeSession(
            id = PeripheralSessionId("unrelated"),
            initialSubscriptions = setOf(
                GattCharacteristicId(
                    "84f7e122-63fd-4f79-8b08-5b9780a36a94".toUuid(),
                ),
            ),
        )
        val unsubscribed = FakeSession(id = PeripheralSessionId("unsubscribed"))
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = setOf(subscribed, unrelated, unsubscribed)
        }
        val queue = FakeQueue()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )

        controller.setPayloadText("hello")
        controller.sendNotification()

        val call = queue.sendCalls.single()
        assertSame(subscribed, call.session)
        assertEquals(EchoGatt.characteristicId, call.characteristic)
        assertContentEquals("hello".encodeToByteArray(), call.value)
        assertEquals(NotificationMode.Notification, call.mode)
    }

    @Test
    fun everyTypedQueueResultIsVisibleInLog() = runTest {
        val results = listOf(
            QueueSendResult.Sent to "Sent",
            QueueSendResult.QueueFull to "QueueFull",
            QueueSendResult.PayloadTooLarge to "PayloadTooLarge",
            QueueSendResult.Disconnected to "Disconnected",
            QueueSendResult.Unsupported to "Unsupported",
            QueueSendResult.Failed(IllegalStateException("boom")) to "Failed: boom",
        )

        results.forEachIndexed { index, (result, expectedLabel) ->
            val session = FakeSession(
                id = PeripheralSessionId("session-$index"),
                initialSubscriptions = setOf(EchoGatt.characteristicId),
            )
            val manager = FakePeripheral().apply {
                mutableState.value = PeripheralManagerState.Running
                mutableSessions.value = setOf(session)
            }
            val controller = PeripheralEchoController(
                runtime = PeripheralExampleRuntime(manager, FakeQueue(result)),
                scope = backgroundScope,
            )

            controller.sendNotification()

            assertTrue(
                controller.state.value.log.last().contains(expectedLabel),
                "Expected queue result label $expectedLabel",
            )
        }
    }

    @Test
    fun setPayloadTextUpdatesStateAndEmptyPayloadPreventsSending() = runTest {
        val session = FakeSession(
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = setOf(session)
        }
        val queue = FakeQueue()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )
        runCurrent()
        assertTrue(controller.state.value.canSend)

        controller.setPayloadText("")

        assertEquals("", controller.state.value.payloadText)
        assertFalse(controller.state.value.canSend)
        controller.sendNotification()
        assertTrue(queue.sendCalls.isEmpty())

        controller.setPayloadText("next")

        assertEquals("next", controller.state.value.payloadText)
        assertTrue(controller.state.value.canSend)
    }

    @Test
    fun multipleSubscribedSessionsReceiveIndependentPayloadCopies() = runTest {
        val first = FakeSession(
            id = PeripheralSessionId("first"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val second = FakeSession(
            id = PeripheralSessionId("second"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = linkedSetOf(first, second)
        }
        val queue = FakeQueue(mutateReceivedValue = true)
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )

        controller.setPayloadText("hello")
        controller.sendNotification()

        assertEquals(listOf(first, second), queue.sendCalls.map { it.session })
        queue.sendCalls.forEach { call ->
            assertContentEquals("hello".encodeToByteArray(), call.value)
        }
        assertEquals(2, controller.state.value.log.size)
        assertTrue(controller.state.value.log[0].contains("first: Sent"))
        assertTrue(controller.state.value.log[1].contains("second: Sent"))
    }

    @Test
    fun suspendedSessionDoesNotBlockOtherSubscribedTargets() = runTest {
        val first = FakeSession(
            id = PeripheralSessionId("first"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val second = FakeSession(
            id = PeripheralSessionId("second"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()
        val queue = FakeQueue(
            behavior = { session ->
                when (session) {
                    first -> {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        QueueSendResult.Sent
                    }

                    second -> {
                        secondCompleted.complete(Unit)
                        QueueSendResult.Sent
                    }

                    else -> error("Unexpected session")
                }
            },
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = linkedSetOf(first, second)
        }
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )

        val send = backgroundScope.async {
            controller.sendNotification()
        }
        runCurrent()

        assertTrue(firstEntered.isCompleted)
        assertTrue(secondCompleted.isCompleted)
        assertFalse(send.isCompleted)

        releaseFirst.complete(Unit)
        send.await()

        assertEquals(setOf(first, second), queue.sendCalls.map { it.session }.toSet())
        assertEquals(
            setOf("first: Sent", "second: Sent"),
            controller.state.value.log.toSet(),
        )
    }

    @Test
    fun ordinaryQueueFailureDoesNotBlockOtherSubscribedTargets() = runTest {
        val first = FakeSession(
            id = PeripheralSessionId("first"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val second = FakeSession(
            id = PeripheralSessionId("second"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val queue = FakeQueue(
            behavior = { session ->
                if (session === first) {
                    throw IllegalStateException("boom")
                }
                QueueSendResult.Sent
            },
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = linkedSetOf(first, second)
        }
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )

        controller.sendNotification()

        assertEquals(setOf(first, second), queue.sendCalls.map { it.session }.toSet())
        assertEquals(
            setOf("first: Failed: boom", "second: Sent"),
            controller.state.value.log.toSet(),
        )
    }

    @Test
    fun subscribedSessionsAreNotSentWhileManagerIsStoppedOrFailed() = runTest {
        val states = listOf(
            PeripheralManagerState.Stopped,
            PeripheralManagerState.Failed(IllegalStateException("unavailable")),
        )

        states.forEach { managerState ->
            val session = FakeSession(
                initialSubscriptions = setOf(EchoGatt.characteristicId),
            )
            val manager = FakePeripheral().apply {
                mutableState.value = managerState
                mutableSessions.value = setOf(session)
            }
            val queue = FakeQueue()
            val controller = PeripheralEchoController(
                runtime = PeripheralExampleRuntime(manager, queue),
                scope = backgroundScope,
            )

            controller.sendNotification()

            assertTrue(
                queue.sendCalls.isEmpty(),
                "Expected no send while manager state is $managerState",
            )
        }
    }

    @Test
    fun unsupportedRuntimeAndNoSubscribedTargetsDoNotSubmitToQueue() = runTest {
        val unsupported = PeripheralEchoController(
            runtime = null,
            scope = backgroundScope,
        )
        unsupported.setPayloadText("hello")
        unsupported.sendNotification()
        assertEquals("hello", unsupported.state.value.payloadText)

        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = setOf(FakeSession())
        }
        val queue = FakeQueue()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )

        controller.sendNotification()

        assertTrue(queue.sendCalls.isEmpty())
    }

    @Test
    fun failedQueueResultWithoutMessageUsesCauseTypeInLog() = runTest {
        val session = FakeSession(
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = setOf(session)
        }
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(
                manager,
                FakeQueue(QueueSendResult.Failed(IllegalStateException())),
            ),
            scope = backgroundScope,
        )

        controller.sendNotification()

        assertTrue(
            controller.state.value.log.single()
                .contains("Failed: IllegalStateException"),
        )
    }

    @Test
    fun queueCancellationPropagatesWithoutLogging() = runTest {
        val cancellation = CancellationException("cancel send")
        val fixture = sendFixture(queueFailure = cancellation)

        val thrown = assertFailsWith<CancellationException> {
            fixture.controller.sendNotification()
        }

        assertPropagatedThrowable(cancellation, thrown)
        assertTrue(fixture.controller.state.value.log.isEmpty())
    }

    @Test
    fun queueErrorPropagatesWithoutLogging() = runTest {
        val error = AssertionError("fatal send")
        val fixture = sendFixture(queueFailure = error)

        val thrown = assertFailsWith<AssertionError> {
            fixture.controller.sendNotification()
        }

        assertPropagatedThrowable(error, thrown)
        assertTrue(fixture.controller.state.value.log.isEmpty())
    }

    @Test
    fun initialRequestReadReturnsDefensiveCopyOfConfiguredDefault() = runTest {
        val fixture = requestFixture()
        val firstResponse = fixture.sendRead(offset = 0)
        runCurrent()

        assertEquals(GattResponseStatus.Success, firstResponse.singleStatus)
        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, firstResponse.singleValue)
        val exposedValue = assertNotNull(firstResponse.singleValue)
        exposedValue[0] = 0

        val secondResponse = fixture.sendRead(offset = 0)
        runCurrent()

        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, secondResponse.singleValue)
    }

    @Test
    fun writeCopiesValueAndReadReturnsItFromRequestedOffset() = runTest {
        val fixture = requestFixture()
        val written = "echo-value".encodeToByteArray()
        val writeResponse = RecordingResponseHandle()

        fixture.manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                offset = 0,
                value = written,
                preparedWrite = false,
                response = writeResponse,
            ),
        )
        written[0] = 0
        runCurrent()

        assertEquals(GattResponseStatus.Success, writeResponse.singleStatus)

        val readResponse = RecordingResponseHandle()
        fixture.manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                offset = 5,
                response = readResponse,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, readResponse.singleStatus)
        assertContentEquals(
            "value Blue Falcon".encodeToByteArray(),
            readResponse.singleValue,
        )
        val returnedValue = assertNotNull(readResponse.singleValue)
        returnedValue[0] = 0
        assertContentEquals(
            "value Blue Falcon".encodeToByteArray(),
            readResponse.singleValue,
        )
    }

    @Test
    fun responseRequiredWriteCommitsOnlyWhenResponseIsResponded() = runTest {
        val fixture = requestFixture()

        fixture.sendWrite(
            offset = 0,
            value = "accepted",
            response = RecordingResponseHandle(
                result = GattResponseResult.Responded,
            ),
        )
        runCurrent()

        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(
            "acceptedom Blue Falcon".encodeToByteArray(),
            storedResponse.singleValue,
        )
    }

    @Test
    fun expiredResponseRequiredWriteDoesNotMutateStoredValue() = runTest {
        val fixture = requestFixture()

        fixture.sendWrite(
            offset = 0,
            value = "expired",
            response = RecordingResponseHandle(
                result = GattResponseResult.Expired,
            ),
        )
        runCurrent()

        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, storedResponse.singleValue)
        assertTrue(fixture.controller.state.value.log.first().contains("staged"))
        assertFalse(fixture.controller.state.value.log.first().contains("wrote"))
    }

    @Test
    fun alreadyRespondedWriteDoesNotMutateStoredValue() = runTest {
        val fixture = requestFixture()

        fixture.sendWrite(
            offset = 0,
            value = "duplicate",
            response = RecordingResponseHandle(
                result = GattResponseResult.AlreadyResponded,
            ),
        )
        runCurrent()

        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, storedResponse.singleValue)
        assertTrue(fixture.controller.state.value.log.first().contains("staged"))
        assertFalse(fixture.controller.state.value.log.first().contains("wrote"))
    }

    @Test
    fun thrownResponseRequiredWriteDoesNotMutateStoredValue() = runTest {
        val fixture = requestFixture()

        fixture.sendWrite(
            offset = 0,
            value = "failed",
            response = RecordingResponseHandle(
                failures = listOf(IllegalStateException("response failed")),
            ),
        )
        runCurrent()

        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, storedResponse.singleValue)
    }

    @Test
    fun echoWriteAtMaximumAttributeBoundaryCommitsWhenResponded() = runTest {
        val fixture = requestFixture()
        val appended = ByteArray(
            MAX_TEST_ECHO_VALUE_SIZE - DEFAULT_TEST_ECHO_VALUE.size,
        ) { 0x2a }

        val response = fixture.sendWrite(
            offset = DEFAULT_TEST_ECHO_VALUE.size,
            value = appended,
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, response.singleStatus)
        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertEquals(MAX_TEST_ECHO_VALUE_SIZE, storedResponse.singleValue?.size)
    }

    @Test
    fun oversizedAppendAndOverlayWritesAreRejectedWithoutMutation() = runTest {
        val fixture = requestFixture()

        val appendResponse = fixture.sendWrite(
            offset = DEFAULT_TEST_ECHO_VALUE.size,
            value = ByteArray(
                MAX_TEST_ECHO_VALUE_SIZE - DEFAULT_TEST_ECHO_VALUE.size + 1,
            ),
        )
        val overlayResponse = fixture.sendWrite(
            offset = 1,
            value = ByteArray(MAX_TEST_ECHO_VALUE_SIZE),
        )
        runCurrent()

        assertEquals(
            GattResponseStatus.InvalidAttributeValueLength,
            appendResponse.singleStatus,
        )
        assertEquals(
            GattResponseStatus.InvalidAttributeValueLength,
            overlayResponse.singleStatus,
        )
        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(DEFAULT_TEST_ECHO_VALUE, storedResponse.singleValue)
    }

    @Test
    fun requestWritesOverlayAtValidOffsetsAndAppendAtCurrentSize() = runTest {
        val fixture = requestFixture()

        fixture.sendWrite(offset = 2, value = "XY")
        fixture.sendWrite(offset = DEFAULT_TEST_ECHO_VALUE.size, value = "!!")
        val response = fixture.sendRead(offset = 0)
        runCurrent()

        assertEquals(GattResponseStatus.Success, response.singleStatus)
        assertContentEquals(
            "HeXYo from Blue Falcon!!".encodeToByteArray(),
            response.singleValue,
        )
    }

    @Test
    fun requestRejectsInvalidReadAndWriteOffsets() = runTest {
        val fixture = requestFixture()

        val negativeWrite = fixture.sendWrite(offset = -1, value = "x")
        val largeWrite = fixture.sendWrite(
            offset = DEFAULT_TEST_ECHO_VALUE.size + 1,
            value = "x",
        )
        val negativeRead = fixture.sendRead(offset = -1)
        val largeRead = fixture.sendRead(offset = DEFAULT_TEST_ECHO_VALUE.size + 1)
        runCurrent()

        listOf(negativeWrite, largeWrite, negativeRead, largeRead).forEach { response ->
            assertEquals(listOf(GattResponseStatus.InvalidOffset), response.statuses)
        }
        val storedResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(
            DEFAULT_TEST_ECHO_VALUE,
            storedResponse.singleValue,
        )
    }

    @Test
    fun requestRejectsUnknownHandlesBeforeOtherValidation() = runTest {
        val fixture = requestFixture()
        val unknownService = GattServiceId(
            "84f7e122-63fd-4f79-8b08-5b9780a36a94".toUuid(),
        )
        val unknownCharacteristic = GattCharacteristicId(
            "84f7e123-63fd-4f79-8b08-5b9780a36a94".toUuid(),
        )
        val serviceResponse = RecordingResponseHandle()
        val characteristicResponse = RecordingResponseHandle()

        fixture.manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = fixture.session,
                serviceId = unknownService,
                characteristicId = EchoGatt.characteristicId,
                offset = -1,
                value = byteArrayOf(1),
                preparedWrite = true,
                response = serviceResponse,
            ),
        )
        fixture.manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = unknownCharacteristic,
                offset = -1,
                response = characteristicResponse,
            ),
        )
        runCurrent()

        assertEquals(listOf(GattResponseStatus.InvalidHandle), serviceResponse.statuses)
        assertEquals(
            listOf(GattResponseStatus.InvalidHandle),
            characteristicResponse.statuses,
        )
    }

    @Test
    fun requestRejectsPreparedBatchDescriptorAndExecuteOperations() = runTest {
        val fixture = requestFixture()
        val descriptorId = GattDescriptorId(
            "00002901-0000-1000-8000-00805f9b34fb".toUuid(),
        )
        val responses = List(6) { RecordingResponseHandle() }

        fixture.manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                offset = 0,
                value = byteArrayOf(1),
                preparedWrite = true,
                response = responses[0],
            ),
        )
        fixture.manager.requestsChannel.send(
            GattCharacteristicWriteBatchRequest(
                session = fixture.session,
                writes = listOf(
                    GattCharacteristicWrite(
                        serviceId = EchoGatt.serviceId,
                        characteristicId = EchoGatt.characteristicId,
                        offset = 0,
                        value = byteArrayOf(1),
                    ),
                ),
                response = responses[1],
            ),
        )
        fixture.manager.requestsChannel.send(
            GattDescriptorReadRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                descriptorId = descriptorId,
                offset = 0,
                response = responses[2],
            ),
        )
        fixture.manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                descriptorId = descriptorId,
                offset = 0,
                value = byteArrayOf(1),
                preparedWrite = false,
                response = responses[3],
            ),
        )
        fixture.manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                descriptorId = descriptorId,
                offset = 0,
                value = byteArrayOf(1),
                preparedWrite = true,
                response = responses[4],
            ),
        )
        fixture.manager.requestsChannel.send(
            GattExecuteWriteRequest(
                session = fixture.session,
                execute = true,
                response = responses[5],
            ),
        )
        runCurrent()

        responses.forEach { response ->
            assertEquals(
                listOf(GattResponseStatus.RequestNotSupported),
                response.statuses,
            )
        }
    }

    @Test
    fun requestWriteWithoutResponseUpdatesStoredValueWithoutResponding() = runTest {
        val fixture = requestFixture()

        fixture.manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                offset = DEFAULT_TEST_ECHO_VALUE.size,
                value = "without-response".encodeToByteArray(),
                preparedWrite = false,
                response = null,
            ),
        )
        runCurrent()

        val readResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertContentEquals(
            "Hello from Blue Falconwithout-response".encodeToByteArray(),
            readResponse.singleValue,
        )
    }

    @Test
    fun requestLogRetainsOnlyNewestHundredEntries() = runTest {
        val fixture = requestFixture()
        val descriptorId = GattDescriptorId(
            "00002901-0000-1000-8000-00805f9b34fb".toUuid(),
        )

        repeat(120) {
            fixture.manager.requestsChannel.send(
                GattDescriptorReadRequest(
                    session = fixture.session,
                    serviceId = EchoGatt.serviceId,
                    characteristicId = EchoGatt.characteristicId,
                    descriptorId = descriptorId,
                    offset = 0,
                    response = RecordingResponseHandle(),
                ),
            )
        }
        runCurrent()

        assertEquals(100, fixture.controller.state.value.log.size)
    }

    @Test
    fun requestTerminalFailureIsLoggedWithoutASecondResponseAttempt() = runTest {
        val fixture = requestFixture()
        val failingResponse = RecordingResponseHandle(
            failures = listOf(IllegalStateException("primary response failed")),
        )

        fixture.manager.requestsChannel.send(
            fixture.readRequest(response = failingResponse),
        )
        runCurrent()

        assertEquals(
            listOf(GattResponseStatus.Success),
            failingResponse.statuses,
        )
        assertEquals(1, failingResponse.respondInvocations)
        assertEquals(1, fixture.controller.state.value.log.size)
        assertTrue(
            fixture.controller.state.value.log.single()
                .contains("primary response failed"),
        )
        val laterResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertEquals(
            GattResponseStatus.Success,
            laterResponse.singleStatus,
        )
    }

    @Test
    fun requestProcessingFailureFallsBackOnceAndCollectorContinues() = runTest {
        val session = FakeSession(
            idFailure = IllegalStateException("session unavailable"),
        )
        val fixture = requestFixture(session)
        val response = RecordingResponseHandle()

        fixture.manager.requestsChannel.send(
            fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(listOf(GattResponseStatus.UnlikelyError), response.statuses)
        assertEquals(1, response.respondInvocations)
        assertEquals(1, fixture.controller.state.value.log.size)
        assertTrue(
            fixture.controller.state.value.log.single()
                .contains("session unavailable"),
        )
        session.idFailure = null
        val laterResponse = fixture.sendRead(offset = 0)
        runCurrent()
        assertEquals(GattResponseStatus.Success, laterResponse.singleStatus)
    }

    @Test
    fun requestProcessingFallbackAlreadyRespondedIsLoggedWithoutRetry() = runTest {
        val fixture = requestFixture(
            FakeSession(idFailure = IllegalStateException("processing failed")),
        )
        val response = RecordingResponseHandle(
            result = GattResponseResult.AlreadyResponded,
        )

        fixture.manager.requestsChannel.send(
            fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(1, response.respondInvocations)
        assertEquals(listOf(GattResponseStatus.UnlikelyError), response.statuses)
        val log = fixture.controller.state.value.log.single()
        assertTrue(log.contains("processing failed"))
        assertTrue(log.contains("already completed"))
    }

    @Test
    fun requestProcessingFallbackExpiredIsLoggedWithoutRetry() = runTest {
        val fixture = requestFixture(
            FakeSession(idFailure = IllegalStateException("processing failed")),
        )
        val response = RecordingResponseHandle(
            result = GattResponseResult.Expired,
        )

        fixture.manager.requestsChannel.send(
            fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(1, response.respondInvocations)
        assertEquals(listOf(GattResponseStatus.UnlikelyError), response.statuses)
        val log = fixture.controller.state.value.log.single()
        assertTrue(log.contains("processing failed"))
        assertTrue(log.contains("expired"))
    }

    @Test
    fun requestFallbackFailureIsLoggedOnceWithoutRecursiveResponse() = runTest {
        val fixture = requestFixture(
            FakeSession(idFailure = IllegalStateException("processing failed")),
        )
        val response = RecordingResponseHandle(
            failures = listOf(IllegalArgumentException("fallback response failed")),
        )

        fixture.manager.requestsChannel.send(fixture.readRequest(response = response))
        runCurrent()

        assertEquals(
            listOf(GattResponseStatus.UnlikelyError),
            response.statuses,
        )
        assertEquals(1, response.respondInvocations)
        val log = fixture.controller.state.value.log.single()
        assertTrue(log.contains("processing failed"))
        assertTrue(log.contains("fallback response failed"))
    }

    @Test
    fun requestFallbackCancellationPropagatesWithoutRecursiveResponse() = runTest {
        val fixture = requestFixtureWithIsolatedScope(
            session = FakeSession(
                idFailure = IllegalStateException("processing failed"),
            ),
        )
        val response = RecordingResponseHandle(
            failures = listOf(CancellationException("cancel fallback")),
        )

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(
            listOf(GattResponseStatus.UnlikelyError),
            response.statuses,
        )
        assertEquals(1, response.respondInvocations)
        assertEquals(1, fixture.fixture.controller.state.value.log.size)
        val laterResponse = RecordingResponseHandle()
        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = laterResponse),
        )
        runCurrent()
        assertTrue(laterResponse.statuses.isEmpty())
        fixture.scopeJob.cancel()
    }

    @Test
    fun requestFallbackErrorPropagatesWithoutRecursiveResponse() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val fixture = requestFixtureWithIsolatedScope(
            uncaught = uncaught,
            session = FakeSession(
                idFailure = IllegalStateException("processing failed"),
            ),
        )
        val error = AssertionError("fatal fallback")
        val response = RecordingResponseHandle(
            failures = listOf(error),
        )

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(
            listOf(GattResponseStatus.UnlikelyError),
            response.statuses,
        )
        assertEquals(1, response.respondInvocations)
        assertEquals(listOf<Throwable>(error), uncaught)
        assertEquals(1, fixture.fixture.controller.state.value.log.size)
        val laterResponse = RecordingResponseHandle()
        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = laterResponse),
        )
        runCurrent()
        assertTrue(laterResponse.statuses.isEmpty())
        fixture.scopeJob.cancel()
    }

    @Test
    fun requestProcessingCancellationPropagatesWithoutResponseOrLogging() = runTest {
        val fixture = requestFixtureWithIsolatedScope(
            session = FakeSession(
                idFailure = CancellationException("cancel processing"),
            ),
        )
        val response = RecordingResponseHandle()

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(0, response.respondInvocations)
        assertTrue(fixture.fixture.controller.state.value.log.isEmpty())
        fixture.scopeJob.cancel()
    }

    @Test
    fun requestProcessingErrorPropagatesWithoutResponseOrLogging() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val error = AssertionError("fatal processing")
        val fixture = requestFixtureWithIsolatedScope(
            uncaught = uncaught,
            session = FakeSession(idFailure = error),
        )
        val response = RecordingResponseHandle()

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(0, response.respondInvocations)
        assertEquals(listOf<Throwable>(error), uncaught)
        assertTrue(fixture.fixture.controller.state.value.log.isEmpty())
        fixture.scopeJob.cancel()
    }

    @Test
    fun requestCancellationPropagatesWithoutFallbackOrLogging() = runTest {
        val fixture = requestFixtureWithIsolatedScope()
        val response = RecordingResponseHandle(
            failures = listOf(CancellationException("cancel request")),
        )

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(listOf(GattResponseStatus.Success), response.statuses)
        assertEquals(1, response.respondInvocations)
        assertTrue(fixture.fixture.controller.state.value.log.isEmpty())
        val laterResponse = RecordingResponseHandle()
        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = laterResponse),
        )
        runCurrent()
        assertTrue(laterResponse.statuses.isEmpty())
        fixture.scopeJob.cancel()
    }

    @Test
    fun requestErrorPropagatesWithoutFallbackOrLogging() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val fixture = requestFixtureWithIsolatedScope(uncaught)
        val error = AssertionError("fatal request")
        val response = RecordingResponseHandle(failures = listOf(error))

        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = response),
        )
        runCurrent()

        assertEquals(listOf(GattResponseStatus.Success), response.statuses)
        assertEquals(1, response.respondInvocations)
        assertEquals(listOf<Throwable>(error), uncaught)
        assertTrue(fixture.fixture.controller.state.value.log.isEmpty())
        val laterResponse = RecordingResponseHandle()
        fixture.fixture.manager.requestsChannel.send(
            fixture.fixture.readRequest(response = laterResponse),
        )
        runCurrent()
        assertTrue(laterResponse.statuses.isEmpty())
        fixture.scopeJob.cancel()
    }

    private fun TestScope.requestFixture(
        session: FakeSession = FakeSession(),
    ): RequestFixture {
        val manager = FakePeripheral()
        return RequestFixture(
            manager = manager,
            session = session,
            controller = PeripheralEchoController(
                runtime = PeripheralExampleRuntime(manager, FakeQueue()),
                scope = backgroundScope,
            ),
        )
    }

    private fun TestScope.sendFixture(
        queueFailure: Throwable,
    ): SendFixture {
        val session = FakeSession(
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val manager = FakePeripheral().apply {
            mutableState.value = PeripheralManagerState.Running
            mutableSessions.value = setOf(session)
        }
        return SendFixture(
            controller = PeripheralEchoController(
                runtime = PeripheralExampleRuntime(
                    manager,
                    FakeQueue(failure = queueFailure),
                ),
                scope = backgroundScope,
            ),
        )
    }

    private fun TestScope.requestFixtureWithIsolatedScope(
        uncaught: MutableList<Throwable> = mutableListOf(),
        session: FakeSession = FakeSession(),
    ): IsolatedRequestFixture {
        val scopeJob = SupervisorJob()
        val handler = CoroutineExceptionHandler { _, cause -> uncaught += cause }
        val scope = CoroutineScope(
            scopeJob + StandardTestDispatcher(testScheduler) + handler,
        )
        val manager = FakePeripheral()
        return IsolatedRequestFixture(
            fixture = RequestFixture(
                manager = manager,
                session = session,
                controller = PeripheralEchoController(
                    runtime = PeripheralExampleRuntime(manager, FakeQueue()),
                    scope = scope,
                ),
            ),
            scopeJob = scopeJob,
        )
    }
}

private data class RequestFixture(
    val manager: FakePeripheral,
    val session: FakeSession,
    val controller: PeripheralEchoController,
) {
    suspend fun sendWrite(
        offset: Int,
        value: String,
        response: RecordingResponseHandle = RecordingResponseHandle(),
    ): RecordingResponseHandle = sendWrite(
        offset = offset,
        value = value.encodeToByteArray(),
        response = response,
    )

    suspend fun sendWrite(
        offset: Int,
        value: ByteArray,
        response: RecordingResponseHandle = RecordingResponseHandle(),
    ): RecordingResponseHandle {
        manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                offset = offset,
                value = value,
                preparedWrite = false,
                response = response,
            ),
        )
        return response
    }

    suspend fun sendRead(
        offset: Int,
        response: RecordingResponseHandle = RecordingResponseHandle(),
    ): RecordingResponseHandle {
        manager.requestsChannel.send(readRequest(offset, response))
        return response
    }

    fun readRequest(
        offset: Int = 0,
        response: RecordingResponseHandle,
    ) = GattCharacteristicReadRequest(
        session = session,
        serviceId = EchoGatt.serviceId,
        characteristicId = EchoGatt.characteristicId,
        offset = offset,
        response = response,
    )
}

private data class IsolatedRequestFixture(
    val fixture: RequestFixture,
    val scopeJob: Job,
)

private data class SendFixture(
    val controller: PeripheralEchoController,
)

private class RecordingResponseHandle(
    failures: List<Throwable> = emptyList(),
    private val result: GattResponseResult = GattResponseResult.Responded,
) : GattResponseHandle {
    private val scriptedFailures = failures.toMutableList()
    private val recordedResponses = mutableListOf<RecordedResponse>()
    private var consumed = false

    var respondInvocations: Int = 0
        private set

    val statuses: List<GattResponseStatus>
        get() = recordedResponses.map { response -> response.status }
    val singleStatus: GattResponseStatus
        get() = recordedResponses.single().status
    val singleValue: ByteArray?
        get() = recordedResponses.single().value?.copyOf()

    override suspend fun respond(
        status: GattResponseStatus,
        value: ByteArray?,
    ): GattResponseResult {
        respondInvocations += 1
        if (consumed) {
            return GattResponseResult.AlreadyResponded
        }
        consumed = true
        recordedResponses += RecordedResponse(status, value?.copyOf())
        if (scriptedFailures.isNotEmpty()) {
            throw scriptedFailures.removeAt(0)
        }
        return result
    }
}

private data class RecordedResponse(
    val status: GattResponseStatus,
    private val copiedValue: ByteArray?,
) {
    val value: ByteArray?
        get() = copiedValue?.copyOf()
}

private class FakePeripheral(
    override val capabilities: PeripheralCapabilities = SUPPORTED_TEST_CAPABILITIES,
) : BlueFalconPeripheral {
    val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState.asStateFlow()

    override val plugins: PeripheralPluginRegistry = UnsupportedPluginRegistry

    val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    override val sessions: StateFlow<Set<PeripheralSession>> =
        mutableSessions.asStateFlow()

    val requestsChannel = Channel<GattServerRequest>(Channel.UNLIMITED)
    override val requests: Flow<GattServerRequest> = requestsChannel.receiveAsFlow()
    override val events: Flow<PeripheralEvent> = emptyFlow()
    override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()
    override val notificationReadinessState: StateFlow<NotificationReadinessState> =
        MutableStateFlow(NotificationReadinessState()).asStateFlow()

    val startConfigs = mutableListOf<PeripheralConfig>()
    var stopCalls = 0
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null

    override suspend fun start(config: PeripheralConfig) {
        startFailure?.let { cause ->
            mutableState.value = PeripheralManagerState.Failed(cause)
            throw cause
        }
        startConfigs += config
        mutableState.value = PeripheralManagerState.Running
    }

    override suspend fun stop() {
        stopFailure?.let { cause ->
            mutableState.value = PeripheralManagerState.Failed(cause)
            throw cause
        }
        stopCalls += 1
        mutableState.value = PeripheralManagerState.Stopped
    }

    override suspend fun close() {
        mutableState.value = PeripheralManagerState.Closed
    }
}

private class FakeQueue(
    var result: QueueSendResult = QueueSendResult.Sent,
    private val failure: Throwable? = null,
    private val mutateReceivedValue: Boolean = false,
    private val behavior: (suspend (PeripheralSession) -> QueueSendResult)? = null,
) : PeripheralQueue {
    val sendCalls = mutableListOf<SendCall>()

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        sendCalls += SendCall(
            session = session,
            characteristic = characteristic,
            value = value.copyOf(),
            mode = mode,
        )
        if (mutateReceivedValue) value.fill(0)
        failure?.let { throw it }
        return behavior?.invoke(session) ?: result
    }
}

private data class SendCall(
    val session: PeripheralSession,
    val characteristic: GattCharacteristicId,
    val value: ByteArray,
    val mode: NotificationMode,
)

private class FakeSession(
    id: PeripheralSessionId = PeripheralSessionId("session-1"),
    initialSubscriptions: Set<GattCharacteristicId> = emptySet(),
    var idFailure: Throwable? = null,
) : PeripheralSession {
    private val sessionId = id

    override val id: PeripheralSessionId
        get() {
            idFailure?.let { cause -> throw cause }
            return sessionId
        }

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Active)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val mutableSubscriptions = MutableStateFlow(initialSubscriptions)
    override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
        mutableSubscriptions.asStateFlow()

    override val maximumUpdateValueLength: StateFlow<Int?> =
        MutableStateFlow<Int?>(null).asStateFlow()
    override val notificationReady: Flow<Unit> = emptyFlow()

    val notifyCalls = mutableListOf<NotifyCall>()

    override suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult {
        notifyCalls += NotifyCall(
            characteristic = characteristic,
            value = value.copyOf(),
            mode = mode,
        )
        return NotificationResult.Sent
    }

    override suspend fun disconnect(): DisconnectResult {
        mutableState.value = SessionState.Closed
        return DisconnectResult.Disconnected
    }
}

private data class NotifyCall(
    val characteristic: GattCharacteristicId,
    val value: ByteArray,
    val mode: NotificationMode,
)

private fun assertPropagatedThrowable(
    expected: Throwable,
    actual: Throwable,
) {
    assertTrue(
        actual === expected || actual.cause === expected,
        "Expected the original throwable or its stack-trace-recovered copy",
    )
}

private val DEFAULT_TEST_ECHO_VALUE = "Hello from Blue Falcon".encodeToByteArray()
private const val MAX_TEST_ECHO_VALUE_SIZE = 512

private val SUPPORTED_TEST_CAPABILITIES = PeripheralCapabilities(
    localGattServer = true,
    connectableAdvertising = true,
    multiCentral = true,
    targetedNotifications = true,
    notificationReadiness = true,
    maximumUpdateValueLength = true,
    forcedDisconnect = false,
    connectionLifecycleVisibility = false,
    preparedWrites = false,
    stateRestoration = true,
)

private object UnsupportedPluginRegistry : PeripheralPluginRegistry {
    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T = error("Plugins are not installed because the queue is injected")
}

package dev.bluefalcon.core

import dev.bluefalcon.core.mocks.FakeBlueFalconEngine
import dev.bluefalcon.core.mocks.FakeCharacteristic
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BlueFalconLifecycleTest {
    @Test fun closeWaitsForAdmittedSynchronousEngineAction() = runTest {
        val engineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob(backgroundScope.coroutineContext.job))
        val engine = LifecycleEngine(engineScope)
        val client = BlueFalcon(engine, ownsEngine = true)
        lateinit var completion: BlueFalconCloseCompletion
        var actionFinished = false
        engine.clearAction = {
            // Unconfined cleanup runs immediately: this is a deterministic interleaving barrier.
            completion = client.requestClose()
            assertFalse(completion.isCompleted, "Close must wait for the already admitted native call")
            assertEquals(0, engine.closeCalls)
            actionFinished = true
        }
        engine.closeAction = { assertTrue(actionFinished) }
        client.clearPeripherals()
        completion.await()
        assertEquals(1, engine.closeCalls)
        assertFailsWith<IllegalStateException> { client.clearPeripherals() }
        engineScope.cancel()
    }

    @Test fun closeWaitsForAdmittedPluginConfigurationAndInstallation() = runTest {
        val engineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob(backgroundScope.coroutineContext.job))
        val engine = LifecycleEngine(engineScope)
        val client = BlueFalcon(engine, ownsEngine = true)
        lateinit var completion: BlueFalconCloseCompletion
        var installed = false
        val plugin = object : NoopPlugin() {
            override fun install(client: BlueFalconClient, config: PluginConfig) { installed = true }
        }
        engine.closeAction = {
            assertTrue(installed)
            assertTrue(plugin in client.plugins.getAll(), "Registry publication belongs to the same admission lease")
        }
        client.plugins.install(plugin) {
            completion = client.requestClose()
            assertFalse(completion.isCompleted, "Close must wait for configure/install/add to finish")
            assertEquals(0, engine.closeCalls)
        }
        completion.await()
        assertEquals(1, engine.closeCalls)
        assertFailsWith<IllegalStateException> { client.plugins.install(NoopPlugin()) }
        engineScope.cancel()
    }

    @Test fun closeReleasesOwnedNativeReadBeforeJoiningFacadeTasks() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val nativeReleased = CompletableDeferred<Unit>()
        var stopped = false
        engine.readAction = {
            try {
                entered.complete(Unit)
                withContext(NonCancellable) { nativeReleased.await() }
                null
            } finally { stopped = true }
        }
        engine.closeAction = { nativeReleased.complete(Unit) }
        val client = BlueFalcon(engine, ownsEngine = true)
        val peer = engine.fake.createFakePeripheral("peer")
        val characteristic = FakeCharacteristic("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val operation = launch { client.readCharacteristic(peer, characteristic) }
        entered.await()
        val closing = async { client.close() }
        try {
            runCurrent()
            assertEquals(1, engine.closeCalls, "Native close must release IO before joining the facade read")
            closing.await()
            assertTrue(stopped)
            operation.join()
            assertTrue(operation.isCancelled)
        } finally { nativeReleased.complete(Unit) }
    }
    @Test fun ownedEngineMayCancelItsOwnScopeWithoutCancellingFacadeCleanup() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        engine.closeAction = { engine.scope.coroutineContext.job.cancel() }
        val client = BlueFalcon(engine, ownsEngine = true)
        client.close()
        assertEquals(1, engine.closeCalls)
        assertTrue(engine.scope.coroutineContext.job.isCancelled)
        assertTrue(client.requestClose().isCompleted)
    }
    @Test fun closingOneFacadeUnsubscribesOnlyItsCollectors() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val first = BlueFalcon(engine)
        val second = BlueFalcon(engine)
        runCurrent()
        assertEquals(listOf(2, 2, 2), engine.subscriberCounts())
        first.close()
        assertEquals(listOf(1, 1, 1), engine.subscriberCounts())
        assertTrue(engine.scope.coroutineContext.job.isActive)
        assertEquals(0, engine.closeCalls)
        second.scan()
        assertTrue(engine.fake.scanCalled)
        second.close()
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
    }

    @Test fun closedFacadeStateAndDerivedFlowStopFollowingEngine() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine)
        val peer = engine.fake.createFakePeripheral("peer")
        val state = client.connectionStateFlow(peer)
        runCurrent()
        engine.fake.emitConnectionStateUpdate(ConnectionStateUpdate(peer, BluetoothPeripheralState.Connected))
        runCurrent()
        assertEquals(PeripheralConnectionState.Connected, state.value)
        client.close()
        engine.fake.emitConnectionStateUpdate(ConnectionStateUpdate(peer, BluetoothPeripheralState.Disconnected))
        runCurrent()
        assertEquals(PeripheralConnectionState.Connected, state.value)
        assertEquals(PeripheralConnectionState.Connected, client.peripheralState(peer))
        assertTrue(engine.scope.coroutineContext.job.children.none(), "No facade job remains under the shared engine")
    }

    @Test fun closeCancelsForwardedSuspendingWork() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        engine.scanAction = { try { entered.complete(Unit); awaitCancellation() } finally { stopped = true } }
        val client = BlueFalcon(engine)
        val operation = launch { client.scan() }
        entered.await()
        client.close()
        assertTrue(stopped)
        operation.join()
        assertTrue(operation.isCancelled)
        assertTrue(engine.scope.coroutineContext.job.isActive)
    }

    @Test fun callerCancellationCancelsForwardedTaskWithoutClosingFacade() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        engine.scanAction = { try { entered.complete(Unit); awaitCancellation() } finally { stopped = true } }
        val client = BlueFalcon(engine)
        val operation = launch { client.scan() }
        entered.await()
        operation.cancelAndJoin()
        runCurrent()
        assertTrue(stopped)
        engine.scanAction = { engine.fake.scan() }
        client.scan()
        client.close()
    }

    @Test fun closeRejectsNewWorkBeforeDelegationOrPluginInstallation() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine)
        val peer = engine.fake.createFakePeripheral("peer")
        val characteristic = FakeCharacteristic("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        client.close()
        assertFailsWith<IllegalStateException> { client.scan() }
        assertFailsWith<IllegalStateException> { client.connect(peer) }
        assertFailsWith<IllegalStateException> { client.readCharacteristic(peer, characteristic) }
        assertFailsWith<IllegalStateException> { client.writeCharacteristic(peer, characteristic, byteArrayOf(1), CharacteristicWriteType.WithResponse) }
        assertFailsWith<IllegalStateException> { client.clearPeripherals() }
        assertFailsWith<IllegalStateException> { client.requestConnectionPriority(peer, ConnectionPriority.High) }
        assertFailsWith<IllegalStateException> { client.refreshGattCache(peer) }
        assertFailsWith<IllegalStateException> { client.connectionStateFlow(peer) }
        assertFailsWith<IllegalStateException> { client.plugins.install(NoopPlugin()) }
        assertFalse(engine.fake.scanCalled)
        assertEquals(0, engine.fake.connectCallCount)
    }

    @Test fun ownedEngineClosesOnceAndFacadeCollectorsStop() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine, ownsEngine = true)
        runCurrent()
        val first = client.requestClose()
        val second = client.requestClose()
        assertSame(first, second)
        first.await()
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
        client.close()
        assertEquals(1, engine.closeCalls)
    }

    @Test fun concurrentCloseWaitsForTheSameOwnedEngineCleanup() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        engine.closeAction = { entered.complete(Unit); release.await() }
        val client = BlueFalcon(engine, ownsEngine = true)
        val first = async { client.close() }
        val second = async { client.close() }
        entered.await()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await(); second.await()
        assertEquals(1, engine.closeCalls)
    }

    @Test fun readOnlyCloseCompletionCannotCancelScheduledCleanup() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine, ownsEngine = true)
        runCurrent()
        val completion = client.requestClose()
        assertFalse((completion as Any) is Job, "Observation handle must not expose cleanup cancellation authority")
        client.close()
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
        assertSame(completion, client.requestClose())
    }

    @Test fun cancellingCompletionWaiterDuringCleanupPreservesLaterFailure() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        engine.closeAction = { entered.complete(Unit); release.await(); error("native teardown failed") }
        val client = BlueFalcon(engine, ownsEngine = true)
        val completion = client.requestClose()
        entered.await()
        val waiter = launch { completion.await() }
        runCurrent()
        waiter.cancelAndJoin()
        release.complete(Unit)
        assertEquals("native teardown failed", assertFailsWith<IllegalStateException> { client.close() }.message)
        assertEquals("native teardown failed", assertFailsWith<IllegalStateException> { completion.await() }.message)
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
    }

    @Test fun cancellationOfCloseCallerDoesNotCancelCleanup() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        engine.closeAction = { entered.complete(Unit); release.await() }
        val client = BlueFalcon(engine, ownsEngine = true)
        val waiter = launch { client.close() }
        entered.await()
        waiter.cancelAndJoin()
        release.complete(Unit)
        client.close()
        assertEquals(1, engine.closeCalls)
    }

    @Test fun ownedEngineFailureIsObservableToEveryCloseCaller() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val failure = IllegalStateException("native close failed")
        engine.closeAction = { throw failure }
        val client = BlueFalcon(engine, ownsEngine = true)
        // JVM coroutine stack recovery may clone Throwable while preserving its cause/message.
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.close() }.message)
        assertEquals(failure.message, assertFailsWith<IllegalStateException> { client.close() }.message)
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
    }

    @Test fun notificationHookCanRequestCloseWithoutJoiningItself() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine, ownsEngine = true)
        val requested = CompletableDeferred<BlueFalconCloseCompletion>()
        client.plugins.install(object : NoopPlugin() {
            override suspend fun onNotificationReceived(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic, value: ByteArray) {
                requested.complete(client.requestClose())
            }
        })
        runCurrent()
        engine.fake.emitCharacteristicNotification(CharacteristicNotification(
            engine.fake.createFakePeripheral("peer"),
            FakeCharacteristic("00002a37-0000-1000-8000-00805f9b34fb".toUuid()),
            byteArrayOf(1),
        ))
        requested.await().await()
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf(0, 0, 0), engine.subscriberCounts())
    }

    @Test fun suspendCloseFromOperationHookCancelsHookAndCompletesCleanup() = runTest {
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon(engine)
        val requested = CompletableDeferred<Unit>()
        client.plugins.install(object : NoopPlugin() {
            override suspend fun onBeforeScan(call: ScanCall): ScanCall {
                requested.complete(Unit)
                client.close()
                return call
            }
        })
        val operation = launch { client.scan() }
        requested.await()
        operation.join()
        client.close()
        assertTrue(operation.isCancelled)
        assertFalse(engine.fake.scanCalled)
    }

    @Test fun engineOwnershipRequiresExplicitCloseCapabilityAndDslPropagatesIt() = runTest {
        assertFailsWith<IllegalArgumentException> { BlueFalcon(FakeBlueFalconEngine(), ownsEngine = true) }
        val engine = LifecycleEngine(backgroundScope)
        val client = BlueFalcon { this.engine = engine; ownsEngine = true }
        client.close()
        assertEquals(1, engine.closeCalls)
    }

    private open class NoopPlugin : BlueFalconPlugin {
        override fun install(client: BlueFalconClient, config: PluginConfig) = Unit
    }

    private class LifecycleEngine(
        override val scope: CoroutineScope,
        val fake: FakeBlueFalconEngine = FakeBlueFalconEngine(),
    ) : ClosableBlueFalconEngine, BlueFalconEngine by fake {
        var scanAction: suspend () -> Unit = { fake.scan() }
        var clearAction: () -> Unit = { fake.clearPeripherals() }
        var readAction: suspend () -> ByteArray? = { null }
        var closeAction: suspend () -> Unit = {}
        var closeCalls = 0
        override suspend fun scan(filters: List<ServiceFilter>) = scanAction()
        override fun clearPeripherals() = clearAction()
        override suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) = readAction()
        override suspend fun close() { closeCalls++; closeAction() }
        fun subscriberCounts() = listOf(characteristicNotifications, connectionStateUpdates, serviceDiscoveryUpdates)
            .map { (it as MutableSharedFlow<*>).subscriptionCount.value }
    }
}

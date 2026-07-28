package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.NotificationSubscriptionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralOperationRegistryTest {

    @Test
    fun `one with-response write owns a peripheral until matching callback`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val connection = registry.connected("peripheral-a")
        val first = connection.operation("characteristic-a")
        val second = connection.operation("characteristic-b")
        val outcomes = mutableListOf<CharacteristicWriteResult>()

        assertTrue(registry.registerWrite(first, outcomes::add))
        assertFalse(registry.registerWrite(second, outcomes::add))
        assertFalse(
            registry.completeWrite(
                first.copy(characteristicUuid = "other"),
                CharacteristicWriteResult.Sent,
            )
        )
        assertTrue(registry.completeWrite(first, CharacteristicWriteResult.Sent))
        assertEquals(
            listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Sent),
            outcomes,
        )
        assertTrue(registry.registerWrite(second, outcomes::add))
    }

    @Test
    fun `subscriptions are owned per peripheral and characteristic`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val connection = registry.connected("peripheral-a")
        val first = connection.operation("characteristic-a")
        val second = connection.operation("characteristic-b")

        assertTrue(registry.registerSubscription(first, enabled = true) {})
        assertFalse(registry.registerSubscription(first, enabled = false) {})
        assertTrue(registry.registerSubscription(second, enabled = true) {})
    }

    @Test
    fun `abandoning waiter retains native ownership until callback`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val connection = registry.connected("peripheral-a")
        val key = connection.operation("characteristic-a")
        val outcomes = mutableListOf<CharacteristicWriteResult>()

        assertTrue(registry.registerWrite(key, outcomes::add))
        assertTrue(registry.abandonWrite(key))
        assertFalse(registry.registerWrite(key, outcomes::add))
        assertTrue(registry.completeWrite(key, CharacteristicWriteResult.Sent))
        assertTrue(outcomes.isEmpty())
        assertTrue(registry.registerWrite(key, outcomes::add))
    }

    @Test
    fun `disconnect completes all operations owned by the connection`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val connection = registry.connected("peripheral-a")
        val writeOutcomes = mutableListOf<CharacteristicWriteResult>()
        val subscriptionOutcomes = mutableListOf<NotificationSubscriptionResult>()

        registry.registerWrite(
            connection.operation("write"),
            writeOutcomes::add,
        )
        registry.registerSubscription(
            connection.operation("notify"),
            enabled = true,
            subscriptionOutcomes::add,
        )
        registry.disconnect(connection)

        assertEquals(
            listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Disconnected),
            writeOutcomes,
        )
        assertEquals(
            listOf<NotificationSubscriptionResult>(
                NotificationSubscriptionResult.Disconnected
            ),
            subscriptionOutcomes,
        )
    }

    @Test
    fun `late callback cannot complete an operation from a newer generation`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val oldConnection = registry.connected("peripheral-a")
        val oldKey = oldConnection.operation("characteristic")
        registry.registerWrite(oldKey) {}
        registry.disconnect(oldConnection)

        val newConnection = registry.connected("peripheral-a")
        val newKey = newConnection.operation("characteristic")
        val outcomes = mutableListOf<CharacteristicWriteResult>()
        registry.registerWrite(newKey, outcomes::add)

        assertFalse(registry.completeWrite(oldKey, CharacteristicWriteResult.Sent))
        assertTrue(outcomes.isEmpty())
        assertTrue(registry.completeWrite(newKey, CharacteristicWriteResult.Sent))
        assertEquals(
            listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Sent),
            outcomes,
        )
    }

    @Test
    fun `late subscription callback cannot complete newer generation`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val oldConnection = registry.connected("peripheral-a")
        val oldKey = oldConnection.operation("characteristic")
        registry.registerSubscription(oldKey, enabled = true) {}
        registry.disconnect(oldConnection)

        val newConnection = registry.connected("peripheral-a")
        val newKey = newConnection.operation("characteristic")
        val outcomes = mutableListOf<NotificationSubscriptionResult>()
        registry.registerSubscription(newKey, enabled = false, outcomes::add)

        assertFalse(
            registry.completeSubscription(
                oldKey,
                NotificationSubscriptionResult.Updated(true),
            )
        )
        assertTrue(outcomes.isEmpty())
        assertTrue(
            registry.completeSubscription(
                newKey,
                NotificationSubscriptionResult.Updated(false),
            )
        )
        assertEquals(
            listOf<NotificationSubscriptionResult>(
                NotificationSubscriptionResult.Updated(false)
            ),
            outcomes,
        )
    }

    @Test
    fun `reconnect terminates old ownership before activating new generation`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val oldConnection = registry.connected("peripheral-a")
        val oldKey = oldConnection.operation("characteristic")
        val oldOutcomes = mutableListOf<CharacteristicWriteResult>()
        registry.registerWrite(oldKey, oldOutcomes::add)

        val newConnection = registry.connected("peripheral-a")

        assertEquals(
            listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Disconnected),
            oldOutcomes,
        )
        assertFalse(registry.completeWrite(oldKey, CharacteristicWriteResult.Sent))
        assertTrue(
            registry.registerWrite(
                newConnection.operation("characteristic"),
            ) {}
        )
    }

    @Test
    fun `readiness snapshot is durable and edge emits only false to true`() = runTest {
        val registry = AppleCentralOperationRegistry()
        val connection = registry.connected("peripheral-a")
        val edges = mutableListOf<AppleCentralConnectionKey>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            registry.readyEdges.collect(edges::add)
        }

        assertFalse(registry.readiness.value.getValue(connection))
        registry.updateReadiness(connection, ready = false)
        registry.updateReadiness(connection, ready = true)
        assertTrue(registry.readiness.value.getValue(connection))
        registry.updateReadiness(connection, ready = true)
        registry.updateReadiness(connection, ready = false)
        registry.updateReadiness(connection, ready = true)

        assertTrue(registry.readiness.value.getValue(connection))
        assertEquals(listOf(connection, connection), edges)
    }

    private fun AppleCentralConnectionKey.operation(
        characteristicUuid: String,
    ) = AppleCentralOperationKey(
        peripheralUuid = peripheralUuid,
        generation = generation,
        characteristicUuid = characteristicUuid,
    )
}

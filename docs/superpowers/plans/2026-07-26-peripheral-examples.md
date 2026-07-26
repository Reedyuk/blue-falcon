# Peripheral Examples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standalone GATT echo-server tutorial and an interactive Central/Peripheral mode to the Compose Multiplatform example using BlueFalconPeripheral and QueuePlugin.

**Architecture:** Each example is self-contained. The Compose application owns one eagerly-created `PeripheralExampleRuntime` containing the platform manager and its single QueuePlugin handle; a common controller translates manager/session/request flows into immutable UI state, and thin ViewModel/Compose layers render it. Android and Apple use production factories, while JVM desktop exposes an explicit unsupported runtime.

**Tech Stack:** Kotlin Multiplatform, Kotlin coroutines and Flow, Compose Multiplatform Material 3, moko-mvvm, Blue Falcon peripheral API, QueuePlugin, Kotlin test/coroutines-test, SwiftUI application bootstrap.

---

## File structure

New focused units:

- `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/PeripheralExampleRuntime.kt`
  — application-owned manager/queue pair and fixed echo UUID/config constants.
- `.../peripheral/presentation/PeripheralServerState.kt`
  — immutable UI state and log-entry model.
- `.../peripheral/presentation/PeripheralEchoController.kt`
  — lifecycle, request routing, session observation, queue sends, and bounded log.
- `.../peripheral/presentation/PeripheralServerViewModel.kt`
  — moko ViewModel wrapper around the controller.
- `.../peripheral/presentation/PeripheralServerView.kt`
  — peripheral screen only.
- `.../commonTest/.../peripheral/presentation/PeripheralEchoControllerTest.kt`
  — common fakes and behavior tests.
- `examples/Peripheral-Example/src/PeripheralEchoServer.kt`
  — standalone common tutorial implementation.
- `examples/Peripheral-Example/README.md`
  — platform construction, lifecycle, permissions, and client exercise guide.

Modified integration files:

- Compose shared Gradle dependencies and AppModule expect/actual files.
- `App.kt` for the Central/Peripheral selector.
- Android and iOS launch code for eager runtime creation.
- Compose and repository example READMEs.

## Task 1: Add runtime ownership and platform factories

**Files:**

- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/build.gradle.kts`
- Create: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/PeripheralExampleRuntime.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/androidMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.android.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/iosMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.ios.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/macosMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.macos.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/jvmMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.desktop.kt`

- [ ] **Step 1: Add QueuePlugin and common-test coroutine dependencies**

Add to `commonMain`:

```kotlin
implementation("dev.bluefalcon:blue-falcon-plugin-queue:$falconVersion")
```

Add to `commonTest`:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
```

- [ ] **Step 2: Create the application-owned runtime**

```kotlin
package com.example.bluefalconcomposemultiplatform.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.plugins.queue.PeripheralQueue
import kotlin.uuid.ExperimentalUuidApi

data class PeripheralExampleRuntime(
    val manager: BlueFalconPeripheral,
    val queue: PeripheralQueue,
)

@OptIn(ExperimentalUuidApi::class)
object EchoGatt {
    const val serviceUuid = "84f7e120-63fd-4f79-8b08-5b9780a36a94"
    const val characteristicUuid = "84f7e121-63fd-4f79-8b08-5b9780a36a94"
    const val restorationIdentifier = "dev.bluefalcon.example.echo-peripheral"
    val serviceId = GattServiceId(serviceUuid.toUuid())
    val characteristicId = GattCharacteristicId(characteristicUuid.toUuid())
}
```

- [ ] **Step 3: Expose the nullable runtime from AppModule**

Add to the expect class:

```kotlin
val peripheralRuntime: PeripheralExampleRuntime?
```

For Android:

```kotlin
private val peripheralManager = createBlueFalconPeripheral(context)
actual val peripheralRuntime = PeripheralExampleRuntime(
    manager = peripheralManager,
    queue = peripheralManager.plugins.install(QueuePlugin) {
        maxPendingItemsPerSession = 64
        maxPendingBytes = 64 * 1024
    },
)
```

Use the same construction with the Apple factory in `iosMain` and `macosMain`. The JVM actual is:

```kotlin
actual val peripheralRuntime: PeripheralExampleRuntime? = null
```

- [ ] **Step 4: Publish current artifacts locally and compile the platform AppModule source sets**

Run from `library/`:

```bash
./gradlew -PversionPeripheral=3.6.1 -PversionPlugins=3.6.1 \
  :peripheral:publishToMavenLocal \
  :plugins:queue:publishToMavenLocal
```

Run from the Compose example:

```bash
./gradlew :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinJvm \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileKotlinMacosArm64
```

Expected: `BUILD SUCCESSFUL`. If the example resolves cached public artifacts, use Maven Local
metadata for the current `versionPeripheral`/`versionPlugins` values rather than changing version
numbers in this feature.

- [ ] **Step 5: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/shared/build.gradle.kts \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/PeripheralExampleRuntime.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/androidMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.android.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/iosMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.ios.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/macosMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.macos.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/jvmMain/kotlin/com/example/bluefalconcomposemultiplatform/di/AppModule.desktop.kt
git commit -m "example: wire peripheral runtime across platforms"
```

## Task 2: Implement lifecycle and observable state with TDD

**Files:**

- Create: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation/PeripheralServerState.kt`
- Create: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation/PeripheralEchoController.kt`
- Create: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonTest/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation/PeripheralEchoControllerTest.kt`

- [ ] **Step 1: Write failing lifecycle/state tests**

Tests must create a `FakePeripheral` with mutable manager/session flows and a `FakeQueue`. Add:

```kotlin
@Test
fun unsupportedRuntimeDisablesActions() = runTest {
    val controller = PeripheralEchoController(null, backgroundScope)
    assertFalse(controller.state.value.supported)
    assertFalse(controller.state.value.canStart)
}

@Test
fun startUsesEchoConfigAndStopCallsManager() = runTest {
    val peripheral = FakePeripheral()
    val controller = PeripheralEchoController(
        PeripheralExampleRuntime(peripheral, FakeQueue()),
        backgroundScope,
    )

    controller.start()
    assertEquals(EchoGatt.serviceUuid, peripheral.startedConfig
        ?.advertiseConfig?.services?.single()?.uuid)
    assertEquals(EchoGatt.restorationIdentifier, peripheral.startedConfig
        ?.restorationIdentifier)

    controller.stop()
    assertEquals(1, peripheral.stopCalls)
}

@Test
fun sessionsAndManagerStateRemainReactive() = runTest {
    val peripheral = FakePeripheral()
    val session = FakeSession(subscriptions = setOf(EchoGatt.characteristicId))
    val controller = PeripheralEchoController(
        PeripheralExampleRuntime(peripheral, FakeQueue()),
        backgroundScope,
    )

    peripheral.mutableState.value = PeripheralManagerState.Running
    peripheral.mutableSessions.value = setOf(session)
    runCurrent()

    assertEquals(PeripheralManagerState.Running, controller.state.value.managerState)
    assertEquals(1, controller.state.value.sessionCount)
    assertEquals(1, controller.state.value.subscribedSessionCount)
}
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :shared:jvmTest \
  --tests '*PeripheralEchoControllerTest.unsupportedRuntimeDisablesActions' \
  --tests '*PeripheralEchoControllerTest.startUsesEchoConfigAndStopCallsManager' \
  --tests '*PeripheralEchoControllerTest.sessionsAndManagerStateRemainReactive'
```

Expected: compilation fails because the state/controller types do not exist.

- [ ] **Step 3: Implement state and lifecycle**

Use an immutable state:

```kotlin
data class PeripheralServerState(
    val supported: Boolean,
    val managerState: PeripheralManagerState = PeripheralManagerState.Stopped,
    val sessionCount: Int = 0,
    val subscribedSessionCount: Int = 0,
    val payloadText: String = "Hello from Blue Falcon",
    val log: List<String> = emptyList(),
) {
    val canStart get() = supported && managerState == PeripheralManagerState.Stopped
    val canStop get() = supported && managerState == PeripheralManagerState.Running
    val canSend get() = canStop && subscribedSessionCount > 0 && payloadText.isNotEmpty()
}
```

`PeripheralEchoController` accepts `PeripheralExampleRuntime?` and a caller-owned `CoroutineScope`.
In `init`, collect manager state and sessions. When sessions change, cancel the previous
subscription-observer job and combine each active session's `subscriptions` StateFlow so the count
stays reactive even when the session set does not change.

The common test doubles implement the full public contracts with mutable flows/channels:

```kotlin
private class FakePeripheral : BlueFalconPeripheral {
    val mutableState = MutableStateFlow<PeripheralManagerState>(PeripheralManagerState.Stopped)
    val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    val requestsChannel = Channel<GattServerRequest>(Channel.UNLIMITED)
    var startedConfig: PeripheralConfig? = null
    var stopCalls = 0

    override val state = mutableState
    override val sessions = mutableSessions
    override val requests = requestsChannel.receiveAsFlow()
    override val capabilities = PeripheralCapabilities.Unsupported
    override val events = emptyFlow<PeripheralEvent>()
    override val notificationReadiness = emptyFlow<NotificationReadiness>()
    override val notificationReadinessState = MutableStateFlow(NotificationReadinessState())
    override val plugins = object : PeripheralPluginRegistry {
        override fun <C : PeripheralPluginConfig, T> install(
            factory: PeripheralPluginFactory<C, T>,
            configure: C.() -> Unit,
        ): T = error("Queue is injected directly into the example runtime")
    }

    override suspend fun start(config: PeripheralConfig) {
        startedConfig = config
        mutableState.value = PeripheralManagerState.Running
    }

    override suspend fun stop() {
        stopCalls++
        mutableState.value = PeripheralManagerState.Stopped
    }

    override suspend fun close() {
        mutableState.value = PeripheralManagerState.Closed
    }
}

private class FakeQueue(
    var result: QueueSendResult = QueueSendResult.Sent,
) : PeripheralQueue {
    val sessions = mutableListOf<PeripheralSession>()
    val values = mutableListOf<ByteArray>()

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        sessions += session
        values += value.copyOf()
        return result
    }
}
```

Build the config exactly once:

```kotlin
private fun echoConfig() = PeripheralConfig(
    advertiseConfig = AdvertiseConfig(
        localName = "Blue Falcon Echo",
        serviceUuids = listOf(EchoGatt.serviceUuid),
        services = listOf(
            GattServiceConfig(
                uuid = EchoGatt.serviceUuid,
                characteristics = listOf(
                    GattCharacteristicConfig(
                        uuid = EchoGatt.characteristicUuid,
                        properties = setOf(
                            CharacteristicProperty.READ,
                            CharacteristicProperty.WRITE,
                            CharacteristicProperty.WRITE_NO_RESPONSE,
                            CharacteristicProperty.NOTIFY,
                            CharacteristicProperty.INDICATE,
                        ),
                        initialValue = DEFAULT_ECHO_VALUE,
                    ),
                ),
            ),
        ),
    ),
    restorationIdentifier = EchoGatt.restorationIdentifier,
)
```

`start()` and `stop()` catch failures and append them to the bounded log.

- [ ] **Step 4: Run lifecycle tests and verify GREEN**

Run the three focused tests. Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonTest/kotlin/com/example/bluefalconcomposemultiplatform/peripheral
git commit -m "example: add peripheral echo lifecycle controller"
```

## Task 3: Route GATT requests safely with TDD

**Files:**

- Modify: `.../peripheral/presentation/PeripheralEchoController.kt`
- Modify: `.../peripheral/presentation/PeripheralServerState.kt`
- Modify: `.../commonTest/.../PeripheralEchoControllerTest.kt`

- [ ] **Step 1: Add failing read/write tests**

Create `RecordingResponseHandle` and construct public request objects. Cover:

```kotlin
@Test
fun writeCopiesValueAndReadReturnsItFromRequestedOffset() = runTest {
    val fixture = fixture()
    val written = "echo-value".encodeToByteArray()

    fixture.peripheral.requestsChannel.send(
        GattCharacteristicWriteRequest(
            session = fixture.session,
            serviceId = EchoGatt.serviceId,
            characteristicId = EchoGatt.characteristicId,
            offset = 0,
            value = written,
            preparedWrite = false,
            response = fixture.writeResponse,
        ),
    )
    written[0] = 0
    runCurrent()
    assertEquals(GattResponseStatus.Success, fixture.writeResponse.status)

    fixture.peripheral.requestsChannel.send(
        GattCharacteristicReadRequest(
            session = fixture.session,
            serviceId = EchoGatt.serviceId,
            characteristicId = EchoGatt.characteristicId,
            offset = 5,
            response = fixture.readResponse,
        ),
    )
    runCurrent()
    assertContentEquals("value".encodeToByteArray(), fixture.readResponse.value)
}
```

Also add:

- invalid read/write offset → `InvalidOffset`;
- unknown service or characteristic → `InvalidHandle`;
- prepared single write, write batch, descriptor read/write, and execute write →
  `RequestNotSupported`;
- a write with `response == null` updates the value without creating a response.

- [ ] **Step 2: Run request tests and verify RED**

Run:

```bash
./gradlew :shared:jvmTest --tests '*PeripheralEchoControllerTest.*Request*' \
  --tests '*PeripheralEchoControllerTest.writeCopiesValueAndReadReturnsItFromRequestedOffset'
```

Expected: failures because requests are not collected or answered.

- [ ] **Step 3: Implement the request router**

Start one request collector in `init` when the runtime is non-null:

```kotlin
runtime.manager.requests.collect { request ->
    try {
        handleRequest(request)
    } catch (cause: Throwable) {
        request.response?.respond(GattResponseStatus.UnlikelyError)
        appendLog("Request failed: ${cause.message ?: cause::class.simpleName}")
    }
}
```

Route by concrete request type. Validation order is:

1. verify `serviceId` and `characteristicId`;
2. reject prepared/batch/descriptor/execute operations;
3. validate offset;
4. copy/update or slice the stored value;
5. respond once when a handle exists;
6. append one concise log entry.

Never call a nullable response handle for a no-response write.

- [ ] **Step 4: Add and pass the bounded-log test**

```kotlin
@Test
fun logRetainsOnlyNewestHundredEntries() = runTest {
    val fixture = fixture()
    repeat(120) {
        fixture.peripheral.requestsChannel.send(
            GattDescriptorReadRequest(
                session = fixture.session,
                serviceId = EchoGatt.serviceId,
                characteristicId = EchoGatt.characteristicId,
                descriptorId = GattDescriptorId(
                    "00002901-0000-1000-8000-00805f9b34fb".toUuid(),
                ),
                offset = 0,
                response = RecordingResponseHandle(),
            ),
        )
    }
    runCurrent()
    assertEquals(100, fixture.controller.state.value.log.size)
}
```

The implementation uses `private const val MAX_LOG_ENTRIES = 100` and keeps
`(current.log + message).takeLast(MAX_LOG_ENTRIES)`.

- [ ] **Step 5: Run the entire controller test class**

Run:

```bash
./gradlew :shared:jvmTest --tests '*PeripheralEchoControllerTest'
```

Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonTest/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation
git commit -m "example: handle peripheral echo requests"
```

## Task 4: Send notifications through QueuePlugin with TDD

**Files:**

- Modify: `.../peripheral/presentation/PeripheralEchoController.kt`
- Modify: `.../commonTest/.../PeripheralEchoControllerTest.kt`

- [ ] **Step 1: Write failing queue-targeting tests**

```kotlin
@Test
fun sendTargetsOnlySessionsSubscribedToEchoCharacteristic() = runTest {
    val subscribed = FakeSession(subscriptions = setOf(EchoGatt.characteristicId))
    val unrelated = FakeSession(
        subscriptions = setOf(
            GattCharacteristicId("00000000-0000-0000-0000-000000000001".toUuid()),
        ),
    )
    val fixture = fixture(sessions = setOf(subscribed, unrelated))

    fixture.controller.setPayloadText("hello")
    fixture.controller.sendNotification()
    advanceUntilIdle()

    assertEquals(listOf(subscribed), fixture.queue.sessions)
    assertContentEquals("hello".encodeToByteArray(), fixture.queue.values.single())
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
    results.forEach { (result, expectedLabel) ->
        val fixture = fixture(queueResult = result)
        fixture.controller.sendNotification()
        advanceUntilIdle()
        assertTrue(fixture.controller.state.value.log.last().contains(expectedLabel))
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Expected: no queue submissions because `sendNotification` does not exist.

- [ ] **Step 3: Implement sendNotification**

Snapshot subscribed sessions before sending:

```kotlin
val targets = runtime.manager.sessions.value.filter {
    EchoGatt.characteristicId in it.subscriptions.value
}
val payload = state.value.payloadText.encodeToByteArray()
targets.forEach { session ->
    val result = runtime.queue.send(
        session = session,
        characteristic = EchoGatt.characteristicId,
        value = payload,
    )
    appendLog("${session.id.value}: ${result.toLogLabel()}")
}
```

Map results explicitly:

```kotlin
private fun QueueSendResult.toLogLabel() = when (this) {
    QueueSendResult.Sent -> "Sent"
    QueueSendResult.QueueFull -> "QueueFull"
    QueueSendResult.PayloadTooLarge -> "PayloadTooLarge"
    QueueSendResult.Disconnected -> "Disconnected"
    QueueSendResult.Unsupported -> "Unsupported"
    is QueueSendResult.Failed -> "Failed: ${cause.message ?: cause::class.simpleName}"
}
```

- [ ] **Step 4: Run all controller tests**

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation/PeripheralEchoController.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonTest/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation/PeripheralEchoControllerTest.kt
git commit -m "example: send peripheral notifications through queue plugin"
```

## Task 5: Add the Compose peripheral screen

**Files:**

- Create: `.../peripheral/presentation/PeripheralServerViewModel.kt`
- Create: `.../peripheral/presentation/PeripheralServerView.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/App.kt`

- [ ] **Step 1: Add the thin ViewModel**

```kotlin
class PeripheralServerViewModel(
    runtime: PeripheralExampleRuntime?,
) : ViewModel() {
    private val controller = PeripheralEchoController(runtime, viewModelScope)
    val state: StateFlow<PeripheralServerState> = controller.state

    fun start() = viewModelScope.launch { controller.start() }
    fun stop() = viewModelScope.launch { controller.stop() }
    fun sendNotification() = viewModelScope.launch { controller.sendNotification() }
    fun setPayloadText(value: String) = controller.setPayloadText(value)
}
```

- [ ] **Step 2: Build the screen from state**

`PeripheralServerView` uses Material 3 and contains:

- an unsupported card when `supported == false`;
- manager-state label and Start/Stop buttons;
- session/subscriber counters;
- `OutlinedTextField` for payload;
- Send button enabled only by `state.canSend`;
- `LazyColumn` showing newest log entries.

Callbacks are explicit parameters rather than passing the ViewModel into nested components.

- [ ] **Step 3: Add the Central/Peripheral selector to App**

Define:

```kotlin
private enum class ExampleMode { Central, Peripheral }
```

Remember the selected mode and render a `SingleChoiceSegmentedButtonRow`. Keep the existing
Central content unchanged in a focused `CentralContent` composable. Lazily create
`PeripheralServerViewModel(appModule.peripheralRuntime)` only in Peripheral mode and render the
new screen.

- [ ] **Step 4: Compile all Compose targets**

Run the four shared compilation tasks from Task 1. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/App.kt \
  examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/peripheral/presentation
git commit -m "example: add interactive peripheral server screen"
```

## Task 6: Demonstrate early application startup

**Files:**

- Modify: `examples/ComposeMultiplatform-3.0-Example/androidBlueFalconExampleMP/src/main/java/com/example/bluefalconcomposemultiplatform/android/MainActivity.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/shared/src/iosMain/kotlin/com/example/bluefalconcomposemultiplatform/MainViewController.kt`
- Modify: `examples/ComposeMultiplatform-3.0-Example/iosBlueFalconExampleMP/iosBlueFalconExampleMP/iOSApp.swift`
- Modify: `examples/ComposeMultiplatform-3.0-Example/iosBlueFalconExampleMP/iosBlueFalconExampleMP/ContentView.swift`
- Modify: `examples/ComposeMultiplatform-3.0-Example/iosBlueFalconExampleMP/iosBlueFalconExampleMP/ComposeView.swift`

- [ ] **Step 1: Move Android AppModule creation before Compose**

Create `val appModule = AppModule(applicationContext)` once in `onCreate`, before `setContent`, and
pass that stable instance into `App`. Add `BLUETOOTH_ADVERTISE` to the Android 12+ permission
request and success check.

- [ ] **Step 2: Change the Kotlin iOS entry point to accept AppModule**

```kotlin
fun MainViewController(appModule: AppModule) = ComposeUIViewController {
    App(
        darkTheme = isDarkTheme(),
        dynamicColor = false,
        appModule = appModule,
    )
}
```

- [ ] **Step 3: Own AppModule from UIApplicationDelegate startup**

```swift
final class AppDelegate: NSObject, UIApplicationDelegate {
    let appModule = AppModule()
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView(appModule: appDelegate.appModule)
        }
    }
}
```

Pass `appModule` through `ContentView` and `ComposeView`, then call
`MainViewControllerKt.MainViewController(appModule: appModule)`. Remove the preview that would
instantiate a second hardware manager.

- [ ] **Step 4: Verify Android and iOS compilation/signatures**

Run:

```bash
./gradlew :shared:compileDebugKotlinAndroid \
  :shared:linkDebugFrameworkIosSimulatorArm64
```

Then run the Xcode project build if the local Xcode scheme is available:

```bash
xcodebuild -project iosBlueFalconExampleMP/iosBlueFalconExampleMP.xcodeproj \
  -scheme iosBlueFalconExampleMP \
  -sdk iphonesimulator \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

Expected: Kotlin framework and Swift call signatures compile.

- [ ] **Step 5: Commit**

```bash
git add examples/ComposeMultiplatform-3.0-Example/androidBlueFalconExampleMP \
  examples/ComposeMultiplatform-3.0-Example/shared/src/iosMain \
  examples/ComposeMultiplatform-3.0-Example/iosBlueFalconExampleMP
git commit -m "example: initialize Apple peripheral during startup"
```

## Task 7: Add the standalone Peripheral-Example

**Files:**

- Create: `examples/Peripheral-Example/src/PeripheralEchoServer.kt`
- Create: `examples/Peripheral-Example/README.md`
- Modify: `examples/README.md`

- [ ] **Step 1: Create the common server example**

The standalone source must show the complete ownership boundary:

```kotlin
class PeripheralEchoServer(
    private val peripheral: BlueFalconPeripheral,
    scope: CoroutineScope,
) {
    private val queue = peripheral.plugins.install(QueuePlugin) {
        maxPendingItemsPerSession = 64
        maxPendingBytes = 64 * 1024
    }
    private var value = "Hello from Blue Falcon".encodeToByteArray()
    private val requestJob = scope.launch {
        peripheral.requests.collect(::handleRequest)
    }

    suspend fun start() = peripheral.start(echoPeripheralConfig())
    suspend fun stop() = peripheral.stop()

    suspend fun notifySubscribers(value: ByteArray): Map<PeripheralSessionId, QueueSendResult> {
        val results = mutableMapOf<PeripheralSessionId, QueueSendResult>()
        peripheral.sessions.value
            .filter { EchoGatt.characteristicId in it.subscriptions.value }
            .forEach { session ->
                results[session.id] =
                    queue.send(session, EchoGatt.characteristicId, value)
            }
        return results
    }

    suspend fun close() {
        requestJob.cancelAndJoin()
        peripheral.close()
    }
}
```

Include the same explicit request-status routing and defensive value copies used by the Compose
controller. Do not introduce `GlobalScope`, platform casts, blocking calls, or hidden fragmentation.

- [ ] **Step 2: Write the README**

Include exact snippets:

```kotlin
// Android application startup
val peripheral = createBlueFalconPeripheral(applicationContext)

// iOS/macOS application startup
val peripheral = createBlueFalconPeripheral()

val server = PeripheralEchoServer(peripheral, applicationScope)
server.start()
```

Document:

- Android `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, and foreground-service ownership;
- Apple early initialization and stable restoration identifier;
- how to connect with a BLE client, write/read the echo characteristic, subscribe, and trigger
  notification send;
- typed QueuePlugin outcomes;
- start/stop versus terminal close;
- application-layer fragmentation/ACK/retry/persistence non-goals.

- [ ] **Step 3: Add Peripheral-Example to the repository index**

Add a new numbered entry to `examples/README.md` and renumber later entries without changing their
content.

- [ ] **Step 4: Validate source references**

Run:

```bash
rg -n 'PeripheralEchoServer|QueuePlugin|createBlueFalconPeripheral|restorationIdentifier' \
  examples/Peripheral-Example examples/README.md
git diff --check
```

Expected: every required concept appears and no whitespace errors are reported.

- [ ] **Step 5: Commit**

```bash
git add examples/Peripheral-Example examples/README.md
git commit -m "example: document peripheral echo server"
```

## Task 8: Update Compose documentation and run the full matrix

**Files:**

- Modify: `examples/ComposeMultiplatform-3.0-Example/README.md`

- [ ] **Step 1: Document the new mode**

Add:

- Central/Peripheral selector;
- echo-service behavior;
- QueuePlugin dependency and typed outcomes;
- early Apple startup wiring;
- Android advertise permission;
- unsupported JVM peripheral mode;
- commands for publishing current local artifacts before building an unreleased example.

Remove stale version snippets that contradict the single `falconVersion` in `shared/build.gradle.kts`.

- [ ] **Step 2: Run focused tests**

```bash
cd examples/ComposeMultiplatform-3.0-Example
./gradlew :shared:jvmTest --tests '*PeripheralEchoControllerTest'
```

Expected: all controller tests pass.

- [ ] **Step 3: Run Compose KMP compilation**

```bash
./gradlew :shared:compileDebugKotlinAndroid \
  :shared:compileKotlinJvm \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileKotlinMacosArm64
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Re-run source library tests**

From `library/`:

```bash
./gradlew :peripheral:allTests :plugins:queue:allTests \
  :peripheral:compileDebugKotlinAndroid :plugins:queue:compileDebugKotlinAndroid \
  :peripheral:compileKotlinIosArm64 :plugins:queue:compileKotlinIosArm64
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Inspect final changes**

```bash
git diff --check upstream/master..HEAD
git status --short --branch
git diff --stat upstream/master..HEAD
```

Confirm the pre-existing untracked `docs/superpowers/plans/` files are not staged accidentally;
stage this plan by its exact path only if it is included in the branch.

- [ ] **Step 6: Commit documentation**

```bash
git add examples/ComposeMultiplatform-3.0-Example/README.md
git commit -m "docs: explain Compose peripheral example"
```

- [ ] **Step 7: Request final code review**

Ask the reviewer to check:

- every response-required request terminates;
- runtime/plugin ownership is single-instance;
- iOS initialization happens before Compose;
- unsupported targets do not claim GATT-server support;
- UI does not own business logic;
- QueuePlugin outcomes remain visible;
- Android/iOS/macOS/JVM source sets compile.

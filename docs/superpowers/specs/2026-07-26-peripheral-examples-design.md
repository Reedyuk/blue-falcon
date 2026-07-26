# Peripheral examples design

## Goal

Add two self-contained examples for the production GATT-server API introduced by ADR 0007:

1. a focused `Peripheral-Example` tutorial that can be read without the Compose application; and
2. an interactive peripheral mode in `ComposeMultiplatform-3.0-Example`.

Both examples use the same echo-service scenario and demonstrate manager lifecycle, request
responses, sessions, subscriptions, targeted notification delivery, and the bounded QueuePlugin.
They cover the Android, iOS, and native macOS implementations currently provided by
`blue-falcon-peripheral`. Unsupported JVM desktop targets remain explicit rather than pretending to
host a GATT server.

## Non-goals

The examples do not implement:

- application protocol framing or fragmentation;
- acknowledgements, retransmission, or durable delivery;
- authentication or encryption;
- reconnect policy;
- background execution policy beyond documenting Android and Apple lifecycle requirements;
- Windows, Linux, JavaScript, or Wasm GATT-server backends.

Those concerns remain application-layer or future-platform work.

## Approach

Use two self-contained examples rather than introducing a shared example-only Gradle module.
This matches the existing `Notification-Example` and `Plugin-Example` structure and lets readers
understand either example independently. The echo-service constants and behavior will be kept small
enough that the limited duplication is clearer than a new dependency boundary.

## Echo service

Both examples expose one primary service and one characteristic with fixed example UUIDs. The
characteristic supports read, write, notify, and indicate where the platform supports them.

The server stores a defensive copy of the most recently accepted characteristic value:

- a characteristic read returns that value, respecting the request offset;
- a characteristic write validates the target characteristic and offset, updates the stored value,
  and sends a success response when a response handle is present;
- unknown characteristics receive `InvalidHandle`, invalid offsets receive `InvalidOffset`, and
  descriptor or execute-write operations receive `RequestNotSupported`;
- no-response writes update state without inventing a platform response.

Every request is recorded as a short human-readable log entry. Unhandled exceptions are converted
to an error log entry; response-required requests are never intentionally left unanswered.

## QueuePlugin use

QueuePlugin is installed exactly once for the lifetime of each `BlueFalconPeripheral`. The example
configures small, visible bounds suitable for a demo:

- 64 pending items per session;
- 64 KiB total pending payload budget.

When the user sends a notification, the controller snapshots the currently subscribed sessions and
calls `PeripheralQueue.send` once per session. Results are aggregated into the UI log without
hiding `QueueFull`, `PayloadTooLarge`, `Disconnected`, `Unsupported`, or `Failed`.

The example does not split values larger than `maximumUpdateValueLength`. It surfaces
`PayloadTooLarge` and explains that fragmentation belongs in the application transport.

## Standalone Peripheral-Example

Add:

```text
examples/Peripheral-Example/
├── README.md
└── src/
    └── PeripheralEchoServer.kt
```

`PeripheralEchoServer.kt` is common Kotlin that accepts an already-created
`BlueFalconPeripheral`, installs QueuePlugin, owns request/session/state collectors, and exposes
`start`, `stop`, `sendNotification`, and `close`.

The README contains:

- Android construction with `createBlueFalconPeripheral(context)`;
- Apple construction with `createBlueFalconPeripheral()`;
- a stable Apple restoration identifier;
- the requirement to instantiate the Apple manager during application startup;
- Android Bluetooth permission and foreground-service notes;
- start/stop/close usage;
- central-side steps for exercising the echo service;
- QueuePlugin result handling and non-goals.

`examples/README.md` gains a Peripheral-Example entry.

## Compose application architecture

### Runtime ownership

Add a common `PeripheralExampleRuntime` containing:

- the platform `BlueFalconPeripheral`; and
- the single installed `PeripheralQueue`.

`AppModule` exposes `peripheralRuntime: PeripheralExampleRuntime?`.

- Android creates it eagerly with the application context.
- iOS and native macOS create it eagerly with the Apple factory.
- JVM desktop returns `null`, because the production peripheral backend is not implemented there.

The iOS Swift entry point owns `AppModule` during application startup and passes it through
`ComposeView` to `MainViewController`. `MainViewController` no longer constructs the module inside
the Compose content lambda. This demonstrates the restoration startup ordering documented in
ADR 0007.

The runtime is application-owned. Navigating away from the peripheral screen stops collectors
owned by its ViewModel but does not close and recreate the manager. The Stop action calls
`BlueFalconPeripheral.stop`; terminal `close` remains an application-shutdown operation.

### Presentation

Add a `peripheral/presentation` package with:

- `PeripheralServerState`;
- `PeripheralServerEvent`;
- `PeripheralServerViewModel`;
- `PeripheralServerView`.

The ViewModel:

- observes manager state and active sessions;
- collects and responds to GATT requests;
- derives subscribed-session counts from each session's subscriptions;
- owns the last echo value and bounded in-memory event log;
- starts and stops the manager;
- sends text entered by the user through QueuePlugin to subscribed sessions;
- exposes unsupported state when `peripheralRuntime` is null.

The UI adds a top-level `Central / Peripheral` selector. Existing scan/detail behavior remains under
Central. Peripheral shows:

- supported/unsupported status;
- manager state;
- Start and Stop controls;
- active and subscribed session counts;
- editable notification payload;
- Send notification action;
- a scrollable, bounded request/result log.

Controls are disabled when their lifecycle preconditions are not met. Errors are shown in state and
the log rather than thrown from UI callbacks.

## Configuration

The example uses:

- a stable local name;
- the echo service UUID in advertising data;
- a stable Apple restoration identifier;
- the same GATT service definition on Android and Apple;
- finite response and inactive-session deadlines from the production defaults unless the example
  needs to display a shorter timeout explicitly.

The Compose example adds the QueuePlugin artifact using its existing `falconVersion`. Local
verification publishes the current library artifacts to Maven Local, which is already restricted
to `dev.bluefalcon` in the example settings.

## Testing and verification

Common tests cover the presentation/controller behavior with fake manager, queue, session, and
response-handle implementations:

- start and stop call the manager once and update state;
- a write updates the echo value and responds successfully;
- a read returns the stored value and honors valid/invalid offsets;
- unknown or unsupported requests receive failure responses;
- notification send targets only subscribed sessions;
- all QueuePlugin results remain visible;
- unsupported runtime disables actions;
- the log remains bounded.

Verification includes:

- the focused common tests;
- Android compilation of the Compose shared module;
- iOS simulator and native macOS compilation of the shared module;
- JVM desktop compilation with the unsupported runtime;
- Swift signature consistency for the changed `MainViewController`;
- the existing peripheral and QueuePlugin test suites;
- `git diff --check`.

## Acceptance criteria

- A reader can copy the standalone example to start an Android or Apple GATT echo server.
- The Compose application can switch between central and peripheral modes without regressing the
  existing central UI.
- Android, iOS, and native macOS use the production peripheral factory.
- Unsupported desktop targets state that the backend is unavailable.
- Every response-required demo request receives exactly one response.
- Notification sending uses QueuePlugin and exposes typed outcomes.
- Apple manager creation is visibly early and uses a stable restoration identifier.
- Example code compiles for the targets included in the verification matrix.

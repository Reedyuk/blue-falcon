# Blue Falcon — Kotlin/Wasm Web Bluetooth Example

A minimal browser app that drives the Blue Falcon **`JsEngine`** compiled to the
**`wasmJs`** target. It scans for a device, connects, discovers services and
characteristics, and lets you read / write / subscribe — with every
`characteristicvaluechanged` notification printed to the on-page log.

It exists to exercise the `wasmJs` variant of `blue-falcon-engine-js`, including
the notification bridge.

## Prerequisites

- A browser that supports **Web Bluetooth** and **WasmGC** — Chrome or Edge 119+.
  Firefox/Safari do not expose Web Bluetooth.
- The page must be served over **`https://`** or **`http://localhost`** (Web
  Bluetooth is blocked on `file://` and plain remote HTTP).
- A BLE peripheral to talk to. A heart-rate strap or any dev board advertising a
  known service works well; for notifications, subscribe to a characteristic that
  pushes updates (e.g. Heart Rate Measurement `00002a37-...`).

## 1. Publish the library to your local Maven repository

This example resolves `dev.bluefalcon:*` from **mavenLocal**, so publish the core
and js/wasmJs engine first (run from this directory):

```bash
./gradlew -p ../../library :core:publishToMavenLocal :engines:js:publishToMavenLocal
```

If you don't have the Android SDK installed, the `core` Android publication will
fail. Publish just the targets this example needs instead:

```bash
./gradlew -p ../../library \
  :core:publishKotlinMultiplatformPublicationToMavenLocal \
  :core:publishWasmJsPublicationToMavenLocal \
  :engines:js:publishToMavenLocal
```

> The version in `build.gradle.kts` (`falconVersion`) must match the library's
> version in `library/gradle.properties` (currently **3.4.3**).

## 2. Run it

```bash
./gradlew wasmJsBrowserDevelopmentRun --continuous
```

Gradle prints a local URL (typically <http://localhost:8080/>). Open it in Chrome
or Edge.

To produce a static bundle instead (output in
`build/dist/wasmJs/productionExecutable/`), run:

```bash
./gradlew wasmJsBrowserDistribution
```

…then serve that folder over `http://localhost` with any static web server.

## 3. Test the flow

1. Optionally enter a **service UUID** to filter the chooser (blank = any device).
2. Click **Request device & connect** and pick your device in the browser prompt.
   (The request must come from this click — that's a Web Bluetooth requirement.)
3. The app discovers services/characteristics and lists them.
4. Use **Read** / **Write** on a characteristic, and **Subscribe** on a notifying
   one. Each pushed update shows up in the log as a `🔔` line — that is the
   `characteristicvaluechanged` event flowing through the wasmJs engine.

## How it's wired

- [`src/wasmJsMain/kotlin/Main.kt`](src/wasmJsMain/kotlin/Main.kt) — creates
  `BlueFalcon(JsEngine())`, drives the BLE flow, and renders a tiny DOM UI via
  `kotlinx.browser`.
- [`src/wasmJsMain/resources/index.html`](src/wasmJsMain/resources/index.html) —
  the page; loads the webpack bundle `blueFalconWasmExample.js`.
- The scan button launches the coroutine `UNDISPATCHED` so `requestDevice()` runs
  inside the click's user-gesture activation.
- Notifications are observed via `blueFalcon.engine.characteristicNotifications`.
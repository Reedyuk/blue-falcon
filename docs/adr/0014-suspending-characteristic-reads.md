# ADR 0014: Suspend `readCharacteristic` Until the Value Is Actually Received

**Status:** Accepted (partially implemented)

**Date:** 2026-09-09

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** [Issue #262](https://github.com/Reedyuk/blue-falcon/issues/262) - `readCharacteristic` returns
before the platform has actually delivered a value, so `characteristic.value` is stale/null
immediately after the call returns - particularly problematic when the read itself triggers an
OS-level pairing dialog, since the value only appears once pairing *and* the read both complete,
well after `readCharacteristic()` has already resumed.

## Context

`BlueFalcon.readCharacteristic(peripheral, characteristic)` is declared `suspend`, which strongly
implies "this suspends until the operation completes and the result is available." In practice,
on every engine except JS, it does not:

```kotlin
// core/BlueFalcon.kt
suspend fun readCharacteristic(peripheral: BluetoothPeripheral, characteristic: BluetoothCharacteristic) {
    plugins.interceptRead(ReadCall(peripheral, characteristic)) { call ->
        runCatching {
            engine.readCharacteristic(call.peripheral, call.characteristic)
            call.characteristic.value
        }
    }
}
```

Two compounding problems:

1. **`engine.readCharacteristic()` is fire-and-forget on 5 of 6 engines.** It issues the native
   read request and returns immediately, *before* the corresponding native completion callback
   (`onCharacteristicRead` on Android, `didUpdateValueForCharacteristic` on Apple, the JNI
   callback on Windows/macOS-JVM, the native read call on RPi) has actually run. Only the **JS
   engine** is correct today, because Web Bluetooth's `characteristic.readValue()` is itself a
   promise that resolves with the value - Kotlin/JS `suspend` interop awaits it naturally.

2. **The value that *is* computed is thrown away.** `PluginRegistry.interceptRead` already
   produces a `Result<ByteArray?>` internally (used today for retry decisions and
   `MetricsPlugin`'s byte-count telemetry), but the public function above returns `Unit`,
   discarding it. Even a well-behaved engine's result never reaches the caller.

`characteristic.value` itself is a *live* getter over the native object (`CBCharacteristic.value`
on Apple, `BluetoothGattCharacteristic.value` on Android) - it does eventually reflect the correct
value once the native callback fires and mutates the native object in place. But because
`readCharacteristic()` doesn't wait for that, any code that reads `characteristic.value`
immediately after calling it (the natural, obvious thing to do with a `suspend` function) sees
stale or `null` data. This is exactly what's reported in #262, made worse there because the read
itself triggers OS-level pairing, adding an even longer, more variable delay before the value
actually lands.

Notably, `writeCharacteristic(peripheral, characteristic, value, writeType): CharacteristicWriteResult`
does not have this problem - it already properly suspends via a per-engine completion-gated
operation queue (e.g. Android's `CentralGattOperationGate.trySubmitTyped`, wrapped in
`suspendCancellableCoroutine`) and returns a typed result. Reads never received the equivalent
treatment. Digging into the Android engine specifically, the infrastructure to fix this is
*already half-built and unused*: `readCharacteristic` enqueues via the older, fire-and-forget
`enqueueLegacy` API, but `onCharacteristicRead` already calls `completeOperation(...)` for
`CentralGattOperationType.ReadCharacteristic` - a signal nothing currently listens for.

## Decision

We will make `readCharacteristic` suspend until the platform has actually delivered a result, and
return that result directly, by:

1. **Introducing a `CharacteristicReadResult` sealed type**, mirroring the shape of the existing
   `CharacteristicWriteResult`, e.g.:

   ```kotlin
   sealed interface CharacteristicReadResult {
       data class Success(val value: ByteArray?) : CharacteristicReadResult
       data object Disconnected : CharacteristicReadResult
       data object Unsupported : CharacteristicReadResult
       data class Failed(val cause: Throwable?) : CharacteristicReadResult
   }
   ```

2. **Changing the public signature** to:

   ```kotlin
   suspend fun readCharacteristic(
       peripheral: BluetoothPeripheral,
       characteristic: BluetoothCharacteristic,
   ): CharacteristicReadResult
   ```

   `PluginRegistry.interceptRead` already threads a `Result<ByteArray?>` through
   `onBeforeRead`/`onAfterRead`/retry/`onOperationCompleted` - this becomes the last piece that
   maps that result into `CharacteristicReadResult` and actually returns it, instead of
   discarding it.

3. **Making every native engine's `readCharacteristic()` suspend for the real completion**,
   mirroring the pattern already used for writes:
   - **Android**: switch from `CentralGattOperationGate.enqueueLegacy` to `trySubmitTyped`,
     wrapped in `suspendCancellableCoroutine`, resuming from the outcome delivered to
     `onCharacteristicRead`'s existing (currently unused) `completeOperation(...)` call - the
     same pattern `writeCharacteristic` already uses one-for-one.
   - **Apple (iOS/macOS)**: add a `CompletableDeferred<ByteArray?>` keyed by
     peripheral+characteristic identity, resolved from `onCharacteristicValueUpdated`. Since
     CoreBluetooth funnels both solicited reads *and* unsolicited notifications through the same
     `didUpdateValueForCharacteristic` delegate callback, the deferred must be resolved (and
     removed) opportunistically whenever a value arrives for that characteristic while a read is
     pending, without disturbing the separate `characteristicNotifications`/`notifications` flow
     used for ongoing subscriptions.
   - **Windows / macOS-JVM (JNI)**: bridge the native async completion callback to a
     `CompletableDeferred`/`suspendCancellableCoroutine`, analogous to however each already
     awaits write completion.
   - **RPi / JS**: RPi's native call is presumed synchronous/blocking already (needs
     confirmation during implementation); JS requires no change.
   - Every engine implementation must apply a bounded timeout (consistent with the write path's
     existing per-engine timeouts) so a read that never completes (e.g. peripheral drops mid-read)
     fails fast rather than suspending forever.

4. **Deprecating, not silently breaking, the old signature.** Since this is a binary- and
   source-breaking API change, we will keep a `@Deprecated(..., ReplaceWith(...))` overload
   returning `Unit` for one minor version where feasible, or - if that proves impractical because
   both signatures return `suspend fun ... : X` and can't overload cleanly by return type alone -
   document the breaking change prominently in the changelog/README with a migration snippet, and
   bump the minor version.

## Consequences

### Positive

- Fixes the reported bug directly: callers get the actual bytes back from the suspend call,
  eliminating the race between "read requested" and "value populated."
- Symmetric with `writeCharacteristic`'s existing suspend-and-return-a-result shape - one
  consistent mental model for both read and write across the library.
- Reuses/extends operation-gating infrastructure that already exists per-engine for writes (and,
  on Android, is already half-wired for reads) rather than inventing a new mechanism.
- `RetryPlugin` and `MetricsPlugin` continue to work unchanged - `PluginRegistry.interceptRead`
  already carries `Result<ByteArray?>` end-to-end; this ADR only changes what the outermost
  function does with it.

### Negative

- **Breaking API change** to a widely-used public function; every consumer app (including all
  bundled examples) calling `readCharacteristic()` for its side effect and then separately
  reading `characteristic.value` must be updated to consume the returned result instead.
- Non-trivial, per-engine implementation work across 4 native engines (Android, Apple, Windows,
  macOS-JVM), each with a different native completion-callback shape to bridge correctly -
  meaningfully larger in scope than a typical plugin-level change.
- Apple's implementation requires carefully disambiguating "this value update is the response to
  *my* pending read" from "this is an unrelated incoming notification for the same
  characteristic," since CoreBluetooth does not distinguish them at the callback level. Getting
  this wrong risks resolving a read with an unrelated notification's value (or vice versa,
  silently dropping a notification the app was also listening for).
- Introduces a new failure mode (read timeout) that didn't previously exist in an observable way,
  which needs sensible default timeout values per engine (a badly-chosen timeout could make an
  otherwise slow-but-successful pairing-triggered read fail spuriously).

### Neutral

- `characteristic.value`'s live-getter behavior over the native object is unchanged and remains
  a valid (if racier) way to read the "last known" value out-of-band, e.g. from a notification
  handler.

## Alternatives Considered

### Alternative 1: Flow-based read API

Expose reads as `fun readCharacteristicUpdates(peripheral, characteristic): Flow<ByteArray>`
instead of (or alongside) a suspend function, as suggested as an acceptable alternative in the
issue.

**Pros:**
- Naturally accommodates a characteristic that both responds to explicit reads *and* pushes
  notifications through one subscription.
- No single-shot timeout design needed; the flow simply emits whenever a value arrives.

**Cons:**
- Every other read/write operation in the library is suspend-and-return; introducing a Flow-only
  read path for this one operation breaks the library's existing conventions and forces callers
  who just want "read this one value" to manage flow collection/cancellation for what's
  conceptually a single request/response.
- Doesn't cleanly express failure (disconnect mid-read, unsupported characteristic, timeout)
  without wrapping emissions in a `Result`/sealed type anyway - at which point it's no simpler
  than a suspend function returning the same sealed type.

**Why not chosen:** A suspend function returning a typed result is symmetric with
`writeCharacteristic`'s already-established shape and matches the issue's own preferred solution.
Nothing prevents a Flow-based convenience wrapper from being added later on top of the corrected
suspend function, if demand emerges.

### Alternative 2: Leave `readCharacteristic` as-is; document the caveat

Keep the current fire-and-forget behavior, and instead document that consumers must separately
observe `characteristicNotifications`/`notifications` (or poll `characteristic.value`) after
calling `readCharacteristic()`.

**Pros:**
- Zero implementation risk; no breaking change.

**Cons:**
- Leaves a `suspend fun` whose name and signature actively mislead every new consumer - the
  exact bug reported in #262 will keep recurring.
- Forces every read call site to hand-roll the same "wait for the next matching notification"
  logic that this ADR proposes to build once, correctly, inside the engines.

**Why not chosen:** Papering over a misleading API surface with documentation doesn't fix the
underlying defect, and the "wait for value" logic is exactly the kind of subtle, per-platform
concurrency code (deferred resolution, disambiguating reads from notifications, timeouts) that
belongs in the library, not duplicated in every app.

## Implementation Notes

**Progress (2026-09-09):** Core (`CharacteristicReadResult`, `BlueFalcon.readCharacteristic`
signature, `PluginRegistry` wiring unchanged) has landed, along with the JS engine (already
correct - now returns the value it awaits) and the RPi engine (previously mis-assumed to be
synchronous; actually fixed with a `CompletableDeferred` keyed by peripheral+characteristic,
resolved from `BluetoothPeripheralCallback.onCharacteristicUpdate`, with a 10s timeout). Android
now suspends for real too: `readCharacteristic` switched from `CentralGattOperationGate
.enqueueLegacy` to `trySubmitTyped` + `suspendCancellableCoroutine`, mirroring
`writeCharacteristic`'s existing pattern one-for-one, with the actual byte value captured from
`onCharacteristicRead` (stashed per operation key, since `CentralGattOperationOutcome` itself only
carries a status code) and non-success outcomes mapped to typed exceptions instead of a sealed
result, matching every other engine's "return the value or throw" contract. Apple now suspends
for real too: `AppleCentralOperationRegistry` gained a `registerRead`/`completeRead`/`abandonRead`
trio (mirroring its existing write/subscription support, keyed by
`peripheralUuid+generation+characteristicUuid` so it is immune to stale post-reconnect callbacks
and cleans up any pending read with a `Disconnected` outcome when the connection drops).
`AppleEngine.readCharacteristic()` registers a pending read via a new
`AppleCentralWriteController.read(...)` helper, fires `readValueForCharacteristic`, and suspends
(with a 10s timeout, matching RPi) until it resolves. Since CoreBluetooth funnels both solicited
reads and unsolicited notifications through the same `didUpdateValueForCharacteristic` delegate
callback, `onCharacteristicValueUpdated` now also calls
`AppleCentralWriteController.onCharacteristicValueReceived(...)` for every callback invocation -
resolving a pending read for that exact characteristic if one exists - while leaving the existing
notification emission path (`_characteristicNotifications.tryEmit`) completely untouched, so a
notification arriving while a read is pending is neither dropped nor mistaken for the read's
result. Covered by a new `AppleCentralReadTest.kt` (disambiguation, native-error propagation,
disconnect cleanup, and generation isolation across reconnects). Windows and macOS-JVM still
implement the new `ByteArray?`-returning engine signature by returning `characteristic.value`
immediately after firing the native read - functionally unchanged (same race condition as before)
but source-compatible, each marked with a `TODO(ADR 0014)` pointing at its real fix, to be landed
in the order below.

- Land core changes first (`CharacteristicReadResult`, `BlueFalcon.readCharacteristic` signature,
  `PluginRegistry` wiring) behind the new return type, with the JS and RPi engines updated
  alongside (JS needs no behavioral change, only to return the value it already awaits; RPi needs
  confirmation its native call is genuinely blocking).
- Land Android next - the operation-gate infrastructure and `writeCharacteristic` precedent to
  copy already exist in the same file (`AndroidEngine.kt`), making it the lowest-risk platform to
  validate the new shape against real hardware pairing flows (the scenario in #262).
- Land Apple (iOS/macOS) third, given the read-vs-notification disambiguation risk called out
  above; add a unit/integration test that asserts a pending read resolves only from a value
  update correlated to that specific read request, not from an unrelated incoming notification.
- Land Windows and macOS-JVM last (JNI bridging), since they affect the smallest user base and
  benefit most from the patterns already proven on Android/Apple.
- Update the bundled examples (`ComposeMultiplatform-3.0-Example`, etc.) and README's usage
  snippets to the new call shape as each engine lands.
- Consider whether `MetricsPlugin`'s `byteCount` telemetry for reads needs any adjustment - it
  should not, since it already sources bytes from `PluginRegistry`'s internal `Result<ByteArray?>`
  rather than the old discarded return value.

## Related Decisions

- [ADR 0008: Structured Per-Peripheral Connection State Machine](0008-structured-peripheral-connection-state-machine.md) -
  similar precedent: a purely additive/corrective core-layer change derived from signals engines
  already emit, to fix a gap between what the API implies and what it actually guarantees.
- [ADR 0012: Metrics/Observability Plugin](0012-metrics-observability-plugin.md) - `MetricsPlugin`
  consumes `PluginRegistry`'s per-operation `Result`/telemetry, which this ADR relies on
  continuing to carry accurate read outcomes end-to-end.

## References

- [Issue #262: blueFalcon readCharacteristic wait on value or supply flow](https://github.com/Reedyuk/blue-falcon/issues/262)

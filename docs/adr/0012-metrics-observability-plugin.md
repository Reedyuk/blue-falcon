# ADR 0012: Metrics/Observability Plugin

**Status:** Proposed

**Date:** 2026-08-27

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** Diagnosing BLE reliability issues in production (connection success rates,
operation latency, throughput) currently requires each consuming app to hand-instrument calls into
`BlueFalcon`, with no shared, reusable way to capture or export these metrics.

## Context

`PluginRegistry` already wraps every scan/connect/read/write/disconnect call through
`onBeforeX`/`onAfterX` interception hooks (used today by `LoggingPlugin`, `CachingPlugin`,
`RetryPlugin`, etc. — see ADR 0002). These hooks see every operation's start, its `Result` outcome,
and (via `RetryCapable`, added when the retry plugin was fixed) how many attempts it took. This is
exactly the instrumentation point a metrics plugin needs: it can observe every operation without
any engine changes, in exactly the same non-invasive way every other plugin in this codebase
already works.

Today there is no metrics or observability plugin, and no OpenTelemetry or Micrometer dependency
anywhere in the codebase. Apps that want to track "what's my connection success rate in the field"
or "how long do reads/writes actually take on real devices" have to build this themselves on top
of raw `BlueFalconDelegate` callbacks or by wrapping `BlueFalcon` calls manually.

## Decision

We will add a `blue-falcon-plugin-metrics` module that hooks into `PluginRegistry`'s existing
interception points to record connection success/failure counts, per-operation latency
histograms, and read/write throughput, exposed through a small **exporter abstraction** so the
core metrics plugin does not force an OpenTelemetry (or any other specific telemetry SDK)
dependency onto every consumer. A separate, optional companion module
(`blue-falcon-plugin-metrics-otel`) provides an OpenTelemetry-backed exporter for apps that want
it.

### New types (`plugins/metrics`)

```kotlin
data class OperationMetric(
    val operation: MetricOperation,     // Scan, Connect, Disconnect, Read, Write
    val peripheralUuid: String?,        // null for Scan, which is not peripheral-scoped
    val success: Boolean,
    val durationMillis: Long,
    val attemptCount: Int,              // 1 if no retry plugin involved, >1 if RetryCapable retried
    val byteCount: Int?,                // populated for Read/Write, for throughput calculation
)

interface MetricsExporter {
    fun record(metric: OperationMetric)
}

class MetricsPlugin(private val config: Config) : BlueFalconPlugin {
    class Config : PluginConfig() {
        var exporters: List<MetricsExporter> = emptyList()
    }

    /** Always-on in-process aggregation, independent of any configured exporter — cheap default visibility. */
    val snapshot: StateFlow<MetricsSnapshot>
}

data class MetricsSnapshot(
    val connectSuccessCount: Long,
    val connectFailureCount: Long,
    val connectLatencyHistogram: Histogram,
    val readLatencyHistogram: Histogram,
    val writeLatencyHistogram: Histogram,
    val bytesRead: Long,
    val bytesWritten: Long,
)

/** Minimal, dependency-free histogram — bucket boundaries configurable, default tuned for BLE latencies (ms). */
class Histogram(boundariesMillis: List<Long> = defaultBleLatencyBuckets) {
    fun record(valueMillis: Long)
    fun snapshotCounts(): Map<Long, Long>   // bucket upper-bound -> count
}
```

`MetricsPlugin` implements `onAfterConnect`/`onAfterRead`/`onAfterWrite`/`onAfterScan` (already
present in `BlueFalconPlugin`), measuring elapsed time itself around `onBeforeX`/`onAfterX` pairs
via a per-call-id timestamp map, and building one `OperationMetric` per completed operation. Every
recorded metric both updates the always-on in-process `snapshot` `StateFlow` (cheap, allocation-
light aggregation with no external dependency) and is forwarded to every configured
`MetricsExporter`.

### Exporter abstraction, not a hard OTel dependency

`blue-falcon-plugin-metrics` depends only on `blue-falcon-core` and kotlinx-coroutines — nothing
telemetry-specific. `MetricsExporter` is a one-method interface; anyone can implement it (log to
console, push to a custom backend, bridge to Firebase/Crashlytics, etc.) without pulling in any
particular SDK.

For teams that specifically want OpenTelemetry, a separate opt-in module
`blue-falcon-plugin-metrics-otel` will provide:

```kotlin
class OpenTelemetryMetricsExporter(meter: Meter) : MetricsExporter {
    // maps OperationMetric -> OTel Counter/Histogram instruments (connect.success, connect.duration, read.bytes, ...)
}
```

This keeps the OpenTelemetry SDK dependency confined to the one artifact that needs it, consistent
with how `blue-falcon-plugin-nordic-fota` already isolates its Nordic-DFU-specific dependency away
from `blue-falcon-core` and every other plugin.

## Consequences

### Positive

- Built entirely on `PluginRegistry`'s existing interception hooks — zero engine changes, and
  consistent with every other plugin's pattern of observing rather than replacing behavior.
- The exporter interface means the core metrics plugin has no dependency on any specific
  telemetry vendor/SDK, so it's just as usable by an app with no observability stack as one on
  OpenTelemetry, Firebase, or something in-house.
- `MetricsSnapshot`/`StateFlow` gives every consumer basic visibility for free (e.g. a debug
  screen showing connect success rate) even without configuring any exporter.
- Naturally captures retry behavior for free: because `RetryCapable`-driven re-invocations already
  flow back through the same `onAfterX` hooks, `attemptCount` in `OperationMetric` reflects real
  retry activity without the metrics plugin needing any special-case integration with the retry
  plugin.

### Negative

- `PluginRegistry`'s current hooks don't have a natural place to record `Scan` start/stop
  duration as a single span (scanning is typically start/stop, not a single before/after pair);
  the metrics plugin will need `onBeforeScan`/`onAfterScan` semantics clarified (or extended
  slightly) to represent "scan session duration" rather than treating it identically to
  connect/read/write. This may require a small, additive change to how scan hooks report timing,
  to be resolved during implementation rather than assumed here.
- Histograms and counts are in-process/in-memory only in the core metrics plugin; anything
  requiring durable storage or cross-process aggregation is the exporter's responsibility, not
  this plugin's.
- Adds a small constant overhead (timestamp capture + map bookkeeping) to every intercepted call,
  though this is the same category of overhead every other plugin already introduces via
  `PluginRegistry`, and is negligible relative to actual BLE operation latency.

### Neutral

- Two new Gradle modules: `blue-falcon-plugin-metrics` (core, no telemetry SDK dependency) and
  `blue-falcon-plugin-metrics-otel` (optional, depends on the OpenTelemetry SDK).

## Alternatives Considered

### Alternative 1: Depend on OpenTelemetry directly in the main metrics plugin

Skip the exporter abstraction and have `blue-falcon-plugin-metrics` itself depend on the OTel SDK.

**Pros:**
- Simpler, one module instead of two; less abstraction to design and maintain.

**Cons:**
- Forces every consumer of basic metrics (even those who just want the in-process
  `MetricsSnapshot` for a debug screen) to pull in the OpenTelemetry SDK and its transitive
  dependencies, which is a meaningfully sized addition for a Kotlin Multiplatform library
  targeting mobile/embedded platforms where binary size matters.
- Locks the plugin to one specific observability vendor's API shape, which may not suit every
  consumer (some may prefer Micrometer, Firebase, or a proprietary backend).

**Why not chosen:** The exporter interface costs one small abstraction and one extra module, and
in exchange keeps the core metrics plugin vendor-neutral and lightweight — directly matching the
user's ask for metrics that are merely "exportable to OpenTelemetry," not OpenTelemetry-only.

### Alternative 2: Build metrics collection into `core` rather than as a plugin

Add success/failure counters and latency tracking directly to `BlueFalcon`/`PluginRegistry`.

**Pros:**
- No opt-in step; every consumer gets metrics automatically.

**Cons:**
- Contrary to ADR 0002's explicit goal of keeping `core` minimal and pushing optional behavior
  into plugins; not every consumer wants the (small but nonzero) overhead of metrics collection.
- `PluginRegistry`'s hook-based design already makes this trivial to add as a plugin with zero
  loss of capability, so there is no technical reason to special-case it into core.

**Why not chosen:** Plugin-based instrumentation is strictly better here: opt-in, zero core
changes, and it can already see everything it needs through existing hooks.

## Implementation Notes

- New modules: `library/plugins/metrics/` (`blue-falcon-plugin-metrics`) and
  `library/plugins/metrics-otel/` (`blue-falcon-plugin-metrics-otel`, depends on `metrics` +
  OpenTelemetry Kotlin/Java SDK).
- Default histogram bucket boundaries should be tuned from real-world BLE operation latencies
  (connects: seconds-scale; reads/writes: tens-of-milliseconds-scale) rather than generic HTTP-style
  buckets.
- Tested the same way the retry plugin was: via `FakeBlueFalconEngine` with configurable
  success/failure/delay behavior, asserting on `MetricsSnapshot` and a fake `MetricsExporter`
  capturing recorded `OperationMetric`s — no OpenTelemetry dependency needed in the core plugin's
  test suite; `metrics-otel`'s own tests are the only place the OTel SDK is exercised.

## Related Decisions

- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md)

## References

- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/plugin/PluginRegistry.kt`
- `library/core/src/commonMain/kotlin/dev/bluefalcon/core/plugin/BlueFalconPlugin.kt` (`RetryCapable`)

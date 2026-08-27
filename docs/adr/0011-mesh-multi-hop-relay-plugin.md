# ADR 0011: Mesh/Multi-Hop Relay Plugin

**Status:** Proposed

**Date:** 2026-08-27

**Deciders:** Blue Falcon maintainers and community contributors

**Technical Story:** Blue Falcon can act as a BLE central (via `BlueFalconEngine`) and, once ADR
0007 lands, as a production-grade peripheral (via `BlueFalconPeripheral`). Combining both roles on
one device is the foundation for relaying data between BLE nodes that are out of direct range of
each other.

## Context

Blue Falcon's existing peripheral-role work establishes the pieces a mesh/relay plugin needs:

- ADR 0006 (Accepted) added a first peripheral-role abstraction (`BluetoothAdvertiser`) used
  today by the device-broadcast plugin to advertise and host a replayed GATT profile.
- ADR 0007 (Proposed, **not yet implemented**) supersedes that abstraction with
  `BlueFalconPeripheral` — a production-grade manager exposing multiple concurrent
  `PeripheralSession`s (one per connected remote central), explicit ATT request/response
  handling, targeted `notify()` per session, and backpressure via `NotificationReadiness`.

A device that runs `BlueFalcon` (central role, connecting out to other nodes) and
`BlueFalconPeripheral` (peripheral role, accepting inbound connections from other nodes)
*simultaneously* has exactly the two roles a BLE mesh relay node needs: it can receive data from
one neighbor as a peripheral and forward it to another neighbor as a central, without any new
low-level transport work — both roles already exist (today for central, and once ADR 0007 ships,
for peripheral) as independent, already-designed capabilities.

What's missing is the mesh-specific logic sitting on top of both roles: message framing, hop
tracking to prevent routing loops, deduplication of relayed messages, and a simple routing
strategy suitable for small ad-hoc BLE networks (e.g. sensor clusters, asset trackers spanning
multiple rooms). None of this belongs in `core` — it's a specialized use case for a subset of
consumers, consistent with ADR 0002's plugin-based approach.

**This ADR is explicitly gated on ADR 0007 landing first.** The mesh plugin depends on
`PeripheralSession`'s targeted `notify()`/backpressure model and `GattServerRequest`'s
explicit-response contract to reliably relay variable-length payloads to multiple simultaneously
connected neighbors; the older `BluetoothAdvertiser` (ADR 0006) broadcasts characteristic updates
to all connected centrals rather than targeting one, which is insufficient for point-to-point
relay hops.

## Decision

Once ADR 0007 ships, we will add a `blue-falcon-plugin-mesh` module providing:

1. A **message envelope and framing format** for payloads larger than a single ATT write/notify
   (using the existing `maximumUpdateValueLength`/`maximumWriteValueLength` negotiated per
   session/link to chunk and reassemble).
2. A **hop-count and message-ID based relay strategy** (flood-with-dedup — the simplest reliable
   strategy for small ad-hoc BLE meshes) to avoid infinite forwarding loops.
3. A unified **`MeshNode`** API that owns both a `BlueFalcon` (central) and a `BlueFalconPeripheral`
   (peripheral) instance and coordinates relaying between them.

### New types (`plugins/mesh`)

```kotlin
data class MeshMessage(
    val id: MeshMessageId,          // random/content-derived ID for dedup
    val originUuid: String,          // uuid of the node that first sent this message
    val hopCount: Int,
    val payload: ByteArray,
)

enum class MeshNodeState { Idle, Running, Stopping, Stopped }

class MeshNode(
    private val central: BlueFalcon,
    private val peripheral: BlueFalconPeripheral,   // from blue-falcon-peripheral (ADR 0007)
    private val config: Config,
) {
    class Config : PluginConfig() {
        var maxHopCount: Int = 5
        var dedupCacheSize: Int = 256
        var dedupTtl: Duration = 30.seconds
    }

    val state: StateFlow<MeshNodeState>
    val inboundMessages: Flow<MeshMessage>   // messages addressed to/observed by this node, post-dedup

    suspend fun start()
    suspend fun stop()

    /** Sends a message, relaying it out to every connected neighbor (central links and peripheral sessions alike). */
    suspend fun broadcast(payload: ByteArray)
}
```

`MeshNode.start()` begins listening for:
- inbound writes on a well-known mesh-relay GATT characteristic via `BlueFalconPeripheral.requests`
  (ADR 0007), reassembling chunked payloads into `MeshMessage`s;
- and independently connects out (via the wrapped `BlueFalcon`) to discovered neighbor peripherals
  advertising the same mesh service UUID.

On receiving a `MeshMessage` that is not already in the dedup cache (keyed by `id`, evicted after
`dedupTtl` or once `dedupCacheSize` is exceeded, LRU) and whose `hopCount < maxHopCount`, the node:
1. emits it on `inboundMessages` for the application to consume;
2. increments `hopCount` and relays it to every other connected neighbor (both directions:
   `PeripheralSession.notify()` to connected centrals, and a GATT write to connected peripherals
   via `BlueFalcon`), excluding the neighbor it was received from.

This is intentionally the simplest correct strategy (flood-with-dedup-and-hop-limit) rather than a
more sophisticated routing table, since BLE's connection-count limits (typically ~7 simultaneous
links on mobile platforms) make small ad-hoc meshes the realistic target, not internet-scale mesh
routing.

## Consequences

### Positive

- Reuses the central role (existing today) and the peripheral role (ADR 0007) entirely as-is —
  no new low-level transport code, only mesh-specific framing/relay logic on top.
- Flood-with-dedup is simple to reason about, test, and audit, and is well-suited to the small
  node counts realistic for BLE (contrasted with larger-scale mesh protocols like Bluetooth Mesh
  itself, which requires specific hardware/stack support this library does not target).
- `MeshNode` composes two already-independent Blue Falcon concepts (`BlueFalcon` +
  `BlueFalconPeripheral`) rather than inventing a third transport, keeping the plugin thin.

### Negative

- **Hard dependency on ADR 0007**, which is still Proposed and unimplemented — this plugin cannot
  begin implementation until that ADR is accepted and its `PeripheralSession`/`notify()`/
  backpressure model exists.
- Flood-with-dedup does not scale to large networks (redundant traffic grows with neighbor count);
  this is an explicit, documented limitation rather than a hidden one.
- Running both central and peripheral roles simultaneously is more battery-intensive than either
  role alone, and background execution constraints (especially iOS) limit how reliably a phone can
  act as a persistent relay node while backgrounded — the plugin will document this as a platform
  constraint, not attempt to work around OS-level background BLE restrictions.
- No built-in encryption/authentication of relayed payloads in v1 — the plugin relays opaque
  bytes; applications requiring secure mesh traffic must encrypt payloads themselves before
  calling `broadcast()`.

### Neutral

- New Gradle module `blue-falcon-plugin-mesh`, depending on both `blue-falcon-core` and
  `blue-falcon-peripheral` (the only plugin in this series with that combined dependency, since
  broadcast/clone depend on peripheral-only and retry/caching/logging depend on core-only).

## Alternatives Considered

### Alternative 1: Implement mesh relaying directly on ADR 0006's `BluetoothAdvertiser`

Avoid waiting for ADR 0007 by building on the already-Accepted broadcast abstraction.

**Pros:**
- Could start immediately without waiting on ADR 0007.

**Cons:**
- `BluetoothAdvertiser` broadcasts characteristic value updates to *all* connected centrals rather
  than targeting one, and has no backpressure/readiness signal — both are needed to reliably relay
  chunked payloads to specific neighbors without overwhelming any single link.
- ADR 0007 explicitly plans to move/retire this abstraction's role in favor of
  `BlueFalconPeripheral`; building mesh on the old API would mean an immediate rewrite once ADR
  0007 ships.

**Why not chosen:** Building on the abstraction already slated for replacement creates near-term
rework for no lasting benefit.

### Alternative 2: Adopt an existing mesh standard (Bluetooth Mesh / Zephyr mesh stack)

Integrate the official Bluetooth SIG Mesh profile instead of a custom flood-with-dedup relay.

**Pros:**
- Standards-based interoperability with other Bluetooth Mesh devices.

**Cons:**
- Bluetooth Mesh requires provisioning, a specific network/application key model, and typically
  platform/stack support Blue Falcon's engines do not currently expose (it is not simply a GATT
  service — it operates over a separate advertising-bearer protocol).
- Vastly larger scope than "relay data between BLE nodes using Blue Falcon's existing
  central/peripheral roles" — would essentially be a new library, not a plugin.

**Why not chosen:** Out of scope for a plugin built from Blue Falcon's existing GATT-based
primitives; consumers needing standards-based mesh should use a dedicated Bluetooth Mesh stack.

## Implementation Notes

- Do not begin implementation until ADR 0007 is Accepted and `blue-falcon-peripheral` ships
  `PeripheralSession`, `GattServerRequest`, and `NotificationReadiness` as described there.
- New module: `library/plugins/mesh/` (`blue-falcon-plugin-mesh`), depending on
  `blue-falcon-core` and `blue-falcon-peripheral`.
- Chunking/reassembly should reuse `maximumUpdateValueLength`/`maximumWriteValueLength` exactly as
  ADR 0007 defines them per session/link, rather than assuming a fixed MTU.

## Related Decisions

- [ADR 0006: BLE Device Broadcast Plugin](0006-ble-device-broadcast-plugin.md)
- [ADR 0007: Introduce a Production-Grade Peripheral/GATT Server Module](0007-introduce-production-grade-peripheral-module.md)

## References

- `docs/adr/0007-introduce-production-grade-peripheral-module.md`

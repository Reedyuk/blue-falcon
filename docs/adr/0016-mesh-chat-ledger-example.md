# ADR 0016: Internet-less Mesh Chat Ledger in the Compose Multiplatform 3.0 Example

**Status:** Accepted

**Date:** 2026-10-01

**Deciders:** Blue Falcon maintainers

**Technical Story:** `ComposeMultiplatform-3.0-Example` already demonstrates the
`blue-falcon-plugin-mesh` (ADR 0011) `MeshNode` via `MeshDemoScreen`/`MeshDemoViewModel` — a
minimal "start mesh, broadcast raw text, show received bytes" demo. This ADR extends that demo
into a self-contained, internet-less group chat.

## Context

`MeshNode` only transports opaque `ByteArray` payloads with transport-level `id`/`originUuid`/
`hopCount` metadata (ADR 0011). It has no concept of a user, a display name, a timestamp, or
message history — any of that is purely an application concern layered on top of `broadcast()`
and `inboundMessages`.

The existing demo reflects that: it shows raw decoded text per message with no sender identity
beyond the 8-character `originUuid` prefix, no timestamps, and no record of who has joined the
mesh. The user asked for something closer to a real (if minimal) product: enter a display name,
join the mesh, and see a WhatsApp-style group chat where both messages and the set of known
participants are kept in sync across every node in the mesh, entirely over BLE with no server and
no internet connectivity.

Separately, the example's only navigation is a `SingleChoiceSegmentedButtonRow` pinned to the top
of the window on every platform (Central / Peripheral / Mesh). That reads fine on desktop/macOS,
but is not how mobile chat-style apps are conventionally navigated — Android and iOS users expect
a bottom tab bar for switching between top-level destinations.

## Decision

### 1. A lightweight, example-owned ledger on top of `MeshNode`

We will not change `blue-falcon-plugin-mesh` itself (no new ADR-0011 surface is needed). Instead,
the example adds its own presentation-layer protocol and ledger, encoded as JSON over the existing
opaque `ByteArray` payload:

```kotlin
@Serializable
sealed interface MeshEnvelope {
    @Serializable
    data class UserJoined(val userId: String, val displayName: String, val timestamp: Long) : MeshEnvelope

    @Serializable
    data class ChatMessage(
        val messageId: String,
        val userId: String,
        val displayName: String,
        val timestamp: Long,
        val text: String,
    ) : MeshEnvelope

    @Serializable
    data class LedgerRequest(val requesterId: String) : MeshEnvelope

    @Serializable
    data class LedgerSync(val users: List<UserJoined>, val recentMessages: List<ChatMessage>) : MeshEnvelope
}
```

`MeshDemoViewModel` maintains a `MeshLedger`: a map of `userId -> UserJoined` and a dedup-by-
`messageId` list of `ChatMessage`, merged from three sources — the user's own name-entry/send
actions, `MeshNode.inboundMessages` (decoded and merged), and `LedgerSync` envelopes. A
`LedgerSync` snapshot (own known users + last 10 messages) is rebroadcast on three *push* triggers,
plus one *pull* trigger:

1. **Edge-triggered:** whenever `MeshNode.neighborCount` increases.
2. **Follow-up:** a one-shot resync 3 seconds after that same edge, since the link can still be
   mid-MTU-negotiation/service-discovery at the instant the count first ticks up, silently
   skipping that first attempt for the affected neighbor (see `MeshNode.relayToCentralNeighbor`/
   `relayToPeripheralSession`'s per-neighbor readiness checks).
3. **Heartbeat fallback:** every 8 seconds while at least one neighbor is connected, regardless of
   whether the count just changed.
4. **Pull/request-response:** a node broadcasts a `LedgerRequest` on join and on every
   neighbor-count increase (with the same 3-second follow-up as above). Any node that receives a
   `LedgerRequest` immediately replies with its own `LedgerSync`, instead of waiting for its next
   heartbeat/edge-trigger.

The push triggers alone were found to be insufficient in practice: in testing, a node that
rejoined after an app kill-and-relaunch could keep showing only itself as a participant - with the
other side's history/participants never arriving - until that other side happened to send a brand
new `ChatMessage` of its own (a single, small, unfragmented payload, unlike the larger multi-
fragment `LedgerSync`). The exact underlying transport condition behind that asymmetry wasn't
pinned down from application-level logs alone, so rather than keep guessing at one specific root
cause, the protocol was made redundant against it: the request/response pair gives the *rejoining*
node an active role in recovering the ledger (it asks, rather than only ever waiting to be told),
on an independent code path (and independent message direction) from the already-open node's own
scheduled pushes. The heartbeat exists for the same reason from the other side: if the underlying
engine is slow (or fails) to settle a node's own neighbor bookkeeping back down after a peer's
connection drops, that peer's later reconnection may not register as a neighbor-count "increase" at
all on the node that still has the history, so its own edge-trigger never fires; the heartbeat
bounds how long that can persist to one interval, independent of how the neighbor-count bookkeeping
behaves. `MeshNode`'s flood-with-dedup relay (ADR 0011) carries every one of these snapshots/
requests to the rest of the mesh.

A `ChatMessage` also implies its sender is a known participant: `MeshLedger.mergeMessage` upserts a
derived `UserJoined` for the message's `userId`/`displayName` if that sender isn't already known (or
is only known from an older announcement). Without this, a sender whose own `UserJoined`/
`LedgerSync` never got through in time (the same early-link race above) would still have their
messages show up in the transcript while never appearing in the participants list — a confusing,
inconsistent state. This makes "who's a participant" robust to delivery order: it no longer depends
solely on the identity envelopes arriving, just on any message from that sender arriving.


This keeps all chat/user-identity/history concerns in the example (where product requirements
like this belong, per ADR 0002's plugin boundary) while reusing `MeshNode` entirely as-is.

### 2. Name entry gates joining the mesh

`MeshDemoScreen` now shows a name-entry step before any mesh controls: the user enters a display
name, which becomes part of every `ChatMessage`/`UserJoined` envelope they send. Pressing "Join"
starts the `MeshNode` and immediately broadcasts a `UserJoined` envelope. The display name lives
only in the view model for the lifetime of the mesh session (no persistence) — consistent with how
transient nicknames work in many ephemeral/ad-hoc chat tools, and avoids adding a fifth
platform-specific storage backend (Android/iOS/macOS/JVM) for this demo.

Because identity isn't persisted, rejoining after the app is killed and relaunched mints a new
random `userId` under the (possibly identical) display name the user re-enters — from the rest of
the mesh's perspective this is indistinguishable from a second person joining. Rather than solve
identity persistence (see Alternative 2 below), the participant list collapses entries to the most
recently announced identity per display name for display purposes only; the underlying per-`userId`
ledger is untouched so message attribution stays correct even across a display-name collision.

### 3. WhatsApp-style rendering

Chat bubbles are grouped by sender (consecutive messages from the same sender omit the repeated
name), right-aligned in an accent color for the local user and left-aligned/neutral for everyone
else, and show a formatted local time per message, matching conventional group-chat UX. The
connection-status header and metrics panel are visually elevated (`CardDefaults.cardElevation`) so
they read as floating above the chat list rather than part of its scrolling content; both the
participant list and the metrics panel are independently collapsible so the user can reclaim
vertical space for the chat transcript.

### 4. Bottom tab bar on Android/iOS, top segmented control elsewhere

We add `expect val useBottomNavigation: Boolean` (`core/presentation`), `actual = true` for
`androidMain`/`iosMain`, `actual = false` for `jvmMain` (desktop) and `macosMain`. `App.kt` branches
on this: Android/iOS render a Material3 `NavigationBar` pinned to the bottom of a `Scaffold`;
desktop/macOS keep the existing top `SingleChoiceSegmentedButtonRow`. Both paths drive the same
`ExampleMode` selection and the same three screens — only the chrome differs.

## Consequences

### Positive

- No changes to `blue-falcon-plugin-mesh`/`blue-falcon-core` — the chat ledger is entirely
  example-level code on top of the already-Accepted ADR 0011 API surface.
- `LedgerSync`-on-neighbor-increase plus follow-up/heartbeat fallbacks give late/re-joiners a
  practical, if eventually-consistent, view of mesh history and participants without inventing a
  new transport-level sync primitive, and bounds recovery time when the increase edge is missed.
- Per-platform navigation chrome matches each platform's UX conventions without duplicating the
  three example screens.

### Negative

- `LedgerSync` resending full history on every neighbor-count increase and every heartbeat tick is
  bandwidth-inefficient on a busy mesh (same flood-with-dedup cost tradeoff already accepted in
  ADR 0011); acceptable for a demo, not a production sync protocol (no vector clocks/deltas).
- Display names are unauthenticated and not persisted; any node can claim any name, and a
  restarted app loses its name and must rejoin under a fresh `userId`, which can transiently show
  as a duplicate participant until the UI's display-name de-dup (most-recent-wins) catches up.
- `LedgerSync` payloads are JSON (`kotlinx-serialization-json`), which is a larger per-byte
  encoding than a compact binary format — fine at demo scale, not optimized for large histories or
  slow links.

### Neutral

- Adds `kotlin("plugin.serialization")` and `kotlinx-serialization-json` to
  `shared/build.gradle.kts`; no other module in this example previously needed serialization.
- Reuses the existing `currentTimeMillis()` expect/actual already present in
  `mesh/presentation` for timestamps.

## Alternatives Considered

### Alternative 1: Add user/message/ledger concepts to `blue-falcon-plugin-mesh` itself

**Pros:** One canonical chat protocol usable by any consumer, not just this example.

**Cons:** Bakes a specific application protocol (users, timestamps, chat semantics) into a
transport plugin whose stated scope (ADR 0011) is opaque-byte relaying; most `MeshNode` consumers
are not chat apps.

**Why not chosen:** Violates the plugin/example boundary — this is product logic, not transport
logic.

### Alternative 2: Persist identity and ledger to local storage per platform

**Pros:** Users keep their name and chat history across restarts.

**Cons:** Requires five platform-specific persistence actuals (Android/iOS/macOS/JVM desktop) for
a demo whose point is to showcase `MeshNode`, not storage patterns.

**Why not chosen:** Out of proportion to the ask; can be added later as a separate, focused change
if wanted.

### Alternative 3: Bottom tab bar on every platform, including desktop/macOS

**Pros:** One navigation chrome implementation, less branching.

**Cons:** Bottom tab bars are not an established desktop/macOS windowed-app convention; the
existing top segmented control already reads naturally there.

**Why not chosen:** The user explicitly asked for a tab bar "for iOS and Android", implying the
other platforms keep their existing chrome.

## Implementation Notes

- New files: `mesh/domain/MeshEnvelope.kt`, `mesh/domain/MeshLedger.kt`,
  `core/presentation/PlatformNavigation.kt` (+ 4 platform actuals).
- `MeshDemoViewModel`/`MeshDemoScreen` extended in place rather than replaced, to keep the existing
  mesh start/stop/metrics wiring intact.
- `App.kt` keeps one `ExampleMode` enum and one `when` for screen content; only the selector chrome
  (`NavigationBar` vs `SingleChoiceSegmentedButtonRow`) is platform-conditional.

## Related Decisions

- [ADR 0011: Mesh/Multi-Hop Relay Plugin](0011-mesh-multi-hop-relay-plugin.md)
- [ADR 0007: Introduce a Production-Grade Peripheral/GATT Server Module](0007-introduce-production-grade-peripheral-module.md)

## References

- `examples/ComposeMultiplatform-3.0-Example/shared/src/commonMain/kotlin/com/example/bluefalconcomposemultiplatform/mesh/`

# ADR 0015: Add an Observable Central Command Queue Plugin

**Status:** ✅ Implemented

**Date:** 2026-10-01

## Context

Blue Falcon's typed central write API exposes portable submission outcomes, maximum payload sizes,
and durable write-readiness state. Applications can therefore respond correctly to platform
backpressure, but every application currently has to build its own buffering, serialization,
cancellation, disconnect cleanup, and monitoring around those primitives.

The existing `blue-falcon-plugin-queue` solves a related problem in the opposite BLE role: it
queues notifications and indications sent by a Blue Falcon peripheral/server to connected central
sessions. It cannot queue commands written by a Blue Falcon central/client to a peripheral.

Android also serializes native GATT operations internally because Android permits only one GATT
operation in flight per connection. That queue is an engine correctness mechanism. It is not a
portable application queue, is not bounded by application policy, and exposes no public command
status stream.

## Decision

Add a separate `blue-falcon-plugin-command-queue` artifact depending only on `blue-falcon-core` and
kotlinx-coroutines.

The plugin queues typed characteristic writes, reads, service and characteristic discovery, MTU
requests, and notification-subscription changes. Each peripheral has one shared FIFO and at most
one queue command in progress. Different peripherals have independent workers and can progress
concurrently. The queue:

- bounds outstanding items per peripheral and bytes globally;
- rejects the newest command when either bound is exceeded;
- copies payload bytes before accepting a command;
- validates known maximum write lengths before submission and again at dispatch time;
- retains a backpressured command at the head of its FIFO and waits for the matching durable
  `characteristicWriteCapabilities` entry to become ready;
- returns confirmed typed read and subscription outcomes;
- waits for matching service/characteristic discovery events with a configurable timeout;
- reports MTU request submission honestly because every engine does not expose a portable
  negotiation-completion result, while also waiting for Android's durable operation gate;
- completes a peripheral's outstanding commands as disconnected when a disconnect event arrives;
- removes commands that are cancelled before platform submission;
- exposes durable `StateFlow<CommandQueueSnapshot>` status and best-effort transition events; and
- has an explicit suspend `close()` operation that completes outstanding work and stops collectors.

The plugin does not fragment messages, persist commands, reconnect peripherals, or retry terminal
failures. Those remain application/protocol decisions.

## Rationale

This is an optional transport policy, so a plugin keeps the core API lightweight and preserves
direct access to the low-level typed write contract. A separate artifact also avoids conflating
central writes with the already-published peripheral notification queue.

The result model preserves the strongest portable guarantee for each operation. Reads and
subscriptions already have typed terminal outcomes. Discovery is correlated with Blue Falcon's
discovery event stream. MTU changes are exposed as requests rather than falsely claiming a
negotiated value. Direct core calls can still bypass this optional application-policy queue; native
engines retain their own correctness scheduling.

The durable state flow is the correctness surface for monitoring. Transition events are explicitly
best effort so a slow diagnostic observer cannot stall BLE work.

## Consequences

Applications that opt in get bounded command buffering and observable progress without duplicating
platform backpressure handling. They must retain the plugin instance, route the GATT operations
that require ordering through it, and call `close()` at teardown.

Operations made directly through `BlueFalcon` bypass the application queue. Native engines still
enforce their own platform safety constraints.

The current central plugin lifecycle has no general uninstall/close callback. This plugin therefore
exposes explicit ownership through `close()`; a future common plugin lifecycle can invoke it
automatically without changing queue semantics.

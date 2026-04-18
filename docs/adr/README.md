# Architecture Decision Records (ADRs)

This directory contains Architecture Decision Records (ADRs) for the Blue Falcon project.

## What is an ADR?

An Architecture Decision Record (ADR) is a document that captures an important architectural decision made along with its context and consequences. ADRs help:

- Preserve the reasoning behind important decisions
- Onboard new team members and AI assistants
- Avoid revisiting settled decisions
- Understand the evolution of the architecture over time

## ADR Naming Convention

ADRs follow this naming pattern:

```
NNNN-title-with-dashes.md
```

Where:
- `NNNN` is a zero-padded sequential number (0001, 0002, etc.)
- `title-with-dashes` is a brief, descriptive title in kebab-case

Examples:
- `0001-use-kotlin-multiplatform.md`
- `0002-adopt-stateflow-for-state-management.md`
- `0003-implement-windows-support-via-jni.md`

## Creating a New ADR

To create a new ADR, use AI assistance by asking:

> "Create a new ADR for [decision topic]"

Or manually:

1. Find the highest numbered ADR in this directory
2. Create a new file with the next number
3. Copy the template from `ADR-TEMPLATE.md`
4. Fill in the sections
5. Commit with a descriptive message

## ADR Status

ADRs can have the following statuses:

- **Proposed** - Under discussion, not yet approved
- **Accepted** - Approved and currently in effect
- **Deprecated** - No longer recommended but not superseded
- **Superseded** - Replaced by another ADR (reference the new ADR number)
- **Rejected** - Considered but not adopted

## Index

<!-- Add links to ADRs here as they are created -->

- [ADR 0001: Add Windows 10 Platform Support Using Native WinRT APIs](0001-add-windows-platform-support.md) - **Accepted** - 2026-04-10
- [ADR 0002: Adopt Plugin-Based Engine Architecture](0002-adopt-plugin-based-engine-architecture.md) - **Proposed** - 2026-04-10
- [ADR 0003: Expose Characteristic Notification Events to Consumers and Plugins](0003-expose-characteristic-notification-events.md) - **Accepted** - 2026-04-18

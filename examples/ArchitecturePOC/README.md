# Architecture Proof of Concept

This directory contains examples demonstrating the new plugin-based engine architecture introduced in ADR 0002.

## Status

⚠️ **These are conceptual examples** - They show the API design and usage patterns for the new 3.0 architecture, but cannot run yet until engines are fully implemented.

## What's Here

### Example.kt

Demonstrates:
1. **Basic Usage** - How to create a BlueFalcon instance with the new DSL API
2. **Custom Plugins** - Creating a LoggingPlugin that intercepts BLE operations
3. **Nordic OTA Plugin** - Concept for vendor-specific firmware update functionality
4. **Multi-Platform Setup** - Pattern for selecting engines based on platform

## Running These Examples

Once Phase 2 (Engine Migration) is complete, these examples will be runnable. For now, they serve as:
- API design validation
- Documentation for future implementation
- Reference for plugin developers

## Related ADR

See [ADR 0002: Adopt Plugin-Based Engine Architecture](../../docs/adr/0002-adopt-plugin-based-engine-architecture.md) for the full architectural decision and implementation plan.

## Current Implementation Status

- ✅ Phase 1: Core module complete (`library/core/`)
- ⏳ Phase 2: Engine migration in progress (`library/engines/android/` started)
- The examples will work once Android engine is fully implemented

## Future Examples

When implementation progresses, this directory will include:
- Working Android app using the new architecture
- iOS example with native engine
- Cross-platform example showing engine switching
- Plugin development examples

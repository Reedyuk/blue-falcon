#!/bin/bash
# Phase 4 Verification Script

set -e

echo "=========================================="
echo "Blue Falcon Phase 4 Verification"
echo "=========================================="
echo ""

echo "1. Checking plugin directory structure..."
echo ""
find library/plugins -type f \( -name "*.kt" -o -name "*.kts" \) | sort
echo ""

echo "2. Verifying settings.gradle.kts includes all plugins..."
echo ""
grep -A 3 "Include plugin modules" library/settings.gradle.kts
echo ""

echo "3. Building all three plugins..."
echo ""
cd library
./gradlew :plugins:logging:build :plugins:retry:build :plugins:caching:build --no-daemon --quiet
echo "✅ All plugins built successfully!"
echo ""

echo "4. Checking plugin classes..."
echo ""
echo "Logging Plugin:"
grep "class LoggingPlugin" plugins/logging/src/commonMain/kotlin/dev/bluefalcon/plugins/logging/LoggingPlugin.kt
echo ""
echo "Retry Plugin:"
grep "class RetryPlugin" plugins/retry/src/commonMain/kotlin/dev/bluefalcon/plugins/retry/RetryPlugin.kt
echo ""
echo "Caching Plugin:"
grep "class CachingPlugin" plugins/caching/src/commonMain/kotlin/dev/bluefalcon/plugins/caching/CachingPlugin.kt
echo ""

echo "=========================================="
echo "✅ Phase 4 Implementation Complete!"
echo "=========================================="
echo ""
echo "Created plugins:"
echo "  • Logging Plugin - Debug and monitor BLE operations"
echo "  • Retry Plugin - Automatic retry with exponential backoff"
echo "  • Caching Plugin - Cache service/characteristic discovery"
echo ""
echo "See PLUGINS_IMPLEMENTATION.md for detailed documentation"
echo "See PHASE4_SUMMARY.md for implementation summary"

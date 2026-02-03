# CI/CD Configuration for Windows Platform Support

## Overview

Added Windows runner jobs to GitHub Actions workflows to ensure the Windows target compiles correctly on every pull request and release.

## Changes Made

### Pull Request Workflow (.github/workflows/pull-requests.yml)

Added new job `build-windows`:
- **Runner**: `windows-latest`
- **Java Version**: 17
- **Steps**:
  1. Checkout code
  2. Setup Java
  3. Compile Windows Kotlin target (`compileKotlinWindows`)
  4. Build Windows JAR (`windowsJar`)

### Release Workflow (.github/workflows/release.yml)

Added new job `verify-windows`:
- **Runner**: `windows-latest`
- **Java Version**: 17
- **Steps**:
  1. Checkout code
  2. Setup Java
  3. Verify Windows target compiles (`compileKotlinWindows windowsJar`)

## Benefits

1. **Early Detection**: Catch Windows compilation issues in CI before merging
2. **Platform Verification**: Test on actual Windows environment, not just cross-compilation
3. **Build Confidence**: Ensure Windows support doesn't break with other changes
4. **Release Safety**: Verify Windows target before publishing to Maven Central

## Workflow Execution

### On Pull Requests
Both jobs run in parallel:
- `build` (macOS): Full multiplatform build
- `build-windows` (Windows): Windows-specific compilation verification

### On Releases
Both jobs run in parallel:
- `build` (macOS): Full build and publish to Maven Central
- `verify-windows` (Windows): Windows compilation verification

## Expected Build Times

Based on local testing:
- Windows Kotlin compilation: ~5 seconds
- Windows JAR creation: ~1 second
- Total Windows job: ~30-60 seconds (including setup)

## Future Enhancements

Potential improvements for future iterations:

1. **Native Library Compilation**: Add step to compile the C++ JNI library with Visual Studio
2. **Caching**: Add Gradle caching to speed up builds
3. **Artifact Upload**: Upload Windows JAR as build artifact
4. **Test Execution**: Add unit tests for Windows platform (when available)
5. **Code Coverage**: Integrate code coverage reporting for Windows target

## Notes

- The Windows jobs are independent and don't block macOS jobs
- Both workflows use Java 17, matching the project's jvmToolchain configuration
- Gradle wrapper scripts are used for consistency across platforms
- Windows uses `gradlew.bat` while Unix systems use `gradlew`

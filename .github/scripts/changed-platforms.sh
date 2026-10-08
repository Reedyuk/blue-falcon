#!/usr/bin/env bash
#
# Finds the build jobs that a set of changed files can affect.
#
# Input:  the changed paths on stdin, one path on each line.
# Output: one "<job>=true" or "<job>=false" line for each build job.
#
# A path that no rule knows selects all the jobs. Thus a new directory gets a
# full build until it has a rule.
set -euo pipefail

jvm=false
android=false
js=false
macos=false
windows=false

all() {
  jvm=true
  android=true
  js=true
  macos=true
  windows=true
}

while IFS= read -r path; do
  case "$path" in
    # The build jobs do not use these files.
    *.md | *.png | docs/* | examples/* | .idea/* | .gitignore | LICENSE | CNAME | _config.yml) ;;

    # A change to the pull request workflow must run all the jobs.
    .github/workflows/pull-requests.yml | .github/scripts/*) all ;;
    .github/*) ;;

    # Engines that have only one platform.
    library/engines/rpi/*) jvm=true ;;
    library/engines/android/*) android=true ;;
    library/engines/js/*) js=true ;;
    library/engines/ios/* | library/engines/macos/* | library/engines/apple-shared/*) macos=true ;;

    # The JNI library of this engine needs the macOS toolchain.
    library/engines/macos-jvm/*) macos=true ;;

    # The Windows engine has JVM code and a native DLL. The JVM target of the
    # legacy module uses the JVM code.
    library/engines/windows/*)
      jvm=true
      windows=true
      ;;
    library/src/windowsMain/cpp/*) windows=true ;;

    *)
      # The name of a source set gives the platform, for example "jvmMain".
      if [[ "$path" =~ ^library/(.+/)?src/([^/]+)/ ]]; then
        case "${BASH_REMATCH[2]}" in
          jvm* | windows*) jvm=true ;;
          android*) android=true ;;
          js* | wasm* | web*) js=true ;;
          # The macOS job builds all the Kotlin/Native targets.
          apple* | ios* | macos* | tvos* | watchos* | native* | linux* | mingw*) macos=true ;;
          # Common code and source sets that have no rule.
          *) all ;;
        esac
      else
        # Build scripts, Gradle properties, the Gradle wrapper, and paths that have no rule.
        all
      fi
      ;;
  esac
done

echo "jvm=$jvm"
echo "android=$android"
echo "js=$js"
echo "macos=$macos"
echo "windows=$windows"

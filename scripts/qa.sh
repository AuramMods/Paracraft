#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# The GameTest server has no window. Its exit code is authoritative for the tests.
mkdir -p build/qa
./gradlew --console=plain test build runGameTestServer "$@" 2>&1 | tee build/qa/headless.log

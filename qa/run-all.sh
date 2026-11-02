#!/usr/bin/env bash
# Runs the project's own suite for the two modules this job touched.
# JAVA_HOME must point at a JDK 21 (see AGENTS.md); this script does not guess it.
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./gradlew :dimblend-spyglass:test :dimblend-experience:test "$@"

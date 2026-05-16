#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'myra-transport harness check failed: %s\n' "$1" >&2
  exit 1
}

require_file() { [[ -f "$ROOT/$1" ]] || fail "missing $1"; }
require_dir() { [[ -d "$ROOT/$1" ]] || fail "missing $1"; }
require_text() { grep -Fq "$2" "$ROOT/$1" || fail "$1 must reference $2"; }

require_file "AGENTS.md"
require_file "docs/INDEX.md"
require_file "docs/WORKFLOW.md"
require_file "docs/quality/README.md"
require_file "settings.gradle.kts"
require_file "build.gradle.kts"

require_text "AGENTS.md" "docs/INDEX.md"
require_text "settings.gradle.kts" "include(\"lib\")"
require_text "settings.gradle.kts" "include(\"myra-server\")"
require_text "settings.gradle.kts" "include(\"benchmarks\")"

require_dir "lib/src/main/java/express/mvp/myra/transport"
require_dir "lib/src/main/java/express/mvp/myra/transport/framing"
require_dir "lib/src/main/java/express/mvp/myra/transport/lifecycle"
require_dir "myra-server/src/main/java/express/mvp/myra/server"

printf 'myra-transport harness check passed\n'

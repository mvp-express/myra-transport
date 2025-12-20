#!/usr/bin/env bash
set -euo pipefail

# Saves the current JMH results.json to a timestamped baseline file.
# Usage:
#   ./scripts/bench_save_baseline.sh [optional-label]

label="${1:-manual}"

ts=$(date -u +"%Y%m%dT%H%M%SZ")
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

src="$root_dir/benchmarks/build/results/jmh/results.json"
dst_dir="$root_dir/benchmarks/baselines"
dst="$dst_dir/${ts}-${label}.json"

if [[ ! -f "$src" ]]; then
  echo "Missing $src. Run :benchmarks:jmh first." >&2
  exit 1
fi

mkdir -p "$dst_dir"
cp "$src" "$dst"

echo "$dst"

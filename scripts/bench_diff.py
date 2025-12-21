#!/usr/bin/env python3

import argparse
import json
import math
import os
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Tuple


@dataclass(frozen=True)
class EntryKey:
    benchmark: str
    params: Tuple[Tuple[str, str], ...]

    def pretty(self) -> str:
        if not self.params:
            return self.benchmark
        params_str = ", ".join(f"{k}={v}" for k, v in self.params)
        return f"{self.benchmark} ({params_str})"


def _load_json(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _as_str_map(obj: Any) -> Dict[str, str]:
    if not isinstance(obj, dict):
        return {}
    out: Dict[str, str] = {}
    for k, v in obj.items():
        out[str(k)] = str(v)
    return out


def _key_for_entry(entry: Dict[str, Any], ignore_params: Iterable[str]) -> EntryKey:
    benchmark = str(entry.get("benchmark", ""))
    params = _as_str_map(entry.get("params", {}))
    for p in ignore_params:
        params.pop(p, None)
    params_t = tuple(sorted(params.items(), key=lambda kv: kv[0]))
    return EntryKey(benchmark=benchmark, params=params_t)


def _get_metric(entry: Dict[str, Any]) -> Dict[str, Any]:
    metric = entry.get("primaryMetric")
    if not isinstance(metric, dict):
        return {}
    return metric


def _get_score(metric: Dict[str, Any]) -> Optional[float]:
    v = metric.get("score")
    if v is None:
        return None
    try:
        return float(v)
    except Exception:
        return None


def _get_unit(metric: Dict[str, Any]) -> str:
    unit = metric.get("scoreUnit")
    return str(unit) if unit is not None else ""


def _get_percentile(metric: Dict[str, Any], p: str) -> Optional[float]:
    percentiles = metric.get("scorePercentiles")
    if not isinstance(percentiles, dict):
        return None
    v = percentiles.get(p)
    if v is None:
        return None
    try:
        return float(v)
    except Exception:
        return None


def _fmt_delta(current: float, baseline: float) -> str:
    delta = current - baseline
    if baseline == 0:
        return f"{delta:+.6g}"
    pct = (delta / baseline) * 100.0
    # Lower is better for us/op. Keep sign so direction is obvious.
    return f"{delta:+.6g} ({pct:+.2f}%)"


def _fmt(v: Optional[float]) -> str:
    if v is None or math.isnan(v):
        return "n/a"
    if abs(v) >= 100:
        return f"{v:.3f}"
    return f"{v:.6f}"


def _index(entries: List[Dict[str, Any]], ignore_params: Iterable[str]) -> Dict[EntryKey, Dict[str, Any]]:
    out: Dict[EntryKey, Dict[str, Any]] = {}
    for e in entries:
        if not isinstance(e, dict):
            continue
        k = _key_for_entry(e, ignore_params)
        if not k.benchmark:
            continue
        out[k] = e
    return out


def _matches_benchmark(key: EntryKey, substring: Optional[str]) -> bool:
    if not substring:
        return True
    return substring in key.benchmark


def compare(current_path: str, baseline_path: str, benchmark_contains: Optional[str], ignore_params: List[str]) -> int:
    current = _load_json(current_path)
    baseline = _load_json(baseline_path)

    if not isinstance(current, list) or not isinstance(baseline, list):
        raise SystemExit("Both files must be JMH JSON arrays")

    current_idx = _index(current, ignore_params)
    baseline_idx = _index(baseline, ignore_params)

    keys = sorted(
        (k for k in current_idx.keys() if k in baseline_idx and _matches_benchmark(k, benchmark_contains)),
        key=lambda k: (k.benchmark, k.params),
    )

    missing_in_baseline = sorted(
        (k for k in current_idx.keys() if k not in baseline_idx and _matches_benchmark(k, benchmark_contains)),
        key=lambda k: (k.benchmark, k.params),
    )
    missing_in_current = sorted(
        (k for k in baseline_idx.keys() if k not in current_idx and _matches_benchmark(k, benchmark_contains)),
        key=lambda k: (k.benchmark, k.params),
    )

    print(f"Current:  {current_path}")
    print(f"Baseline: {baseline_path}")
    if benchmark_contains:
        print(f"Filter: benchmark contains '{benchmark_contains}'")
    if ignore_params:
        print(f"Ignoring params: {', '.join(ignore_params)}")
    print()

    if not keys:
        print("No matching benchmark+params entries found between current and baseline.")
        if missing_in_baseline:
            print(f"Current-only entries: {len(missing_in_baseline)}")
        if missing_in_current:
            print(f"Baseline-only entries: {len(missing_in_current)}")
        return 1

    # For latency benchmarks in us/op, lower is better.
    for k in keys:
        c_entry = current_idx[k]
        b_entry = baseline_idx[k]

        c_metric = _get_metric(c_entry)
        b_metric = _get_metric(b_entry)

        c_score = _get_score(c_metric)
        b_score = _get_score(b_metric)
        unit = _get_unit(c_metric) or _get_unit(b_metric)

        p50_c = _get_percentile(c_metric, "50.0")
        p50_b = _get_percentile(b_metric, "50.0")
        p99_c = _get_percentile(c_metric, "99.0")
        p99_b = _get_percentile(b_metric, "99.0")
        p999_c = _get_percentile(c_metric, "99.9")
        p999_b = _get_percentile(b_metric, "99.9")

        print(k.pretty())
        if c_score is not None and b_score is not None:
            print(f"  mean:   {_fmt(b_score)} -> {_fmt(c_score)} {unit}   Δ {_fmt_delta(c_score, b_score)}")
        else:
            print("  mean:   n/a")

        if p50_b is not None and p50_c is not None:
            print(f"  p50:    {_fmt(p50_b)} -> {_fmt(p50_c)} {unit}   Δ {_fmt_delta(p50_c, p50_b)}")
        if p99_b is not None and p99_c is not None:
            print(f"  p99:    {_fmt(p99_b)} -> {_fmt(p99_c)} {unit}   Δ {_fmt_delta(p99_c, p99_b)}")
        if p999_b is not None and p999_c is not None:
            print(f"  p99.9:  {_fmt(p999_b)} -> {_fmt(p999_c)} {unit}   Δ {_fmt_delta(p999_c, p999_b)}")
        print()

    if missing_in_baseline:
        print(f"Current-only entries (no baseline match): {len(missing_in_baseline)}")
    if missing_in_current:
        print(f"Baseline-only entries (not in current): {len(missing_in_current)}")

    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Compare two JMH results.json files for directional feedback.")
    ap.add_argument("--current", required=True, help="Path to current JMH JSON")
    ap.add_argument("--baseline", required=True, help="Path to baseline JMH JSON")
    ap.add_argument(
        "--benchmark-contains",
        default=None,
        help="Only compare entries whose benchmark name contains this substring.",
    )
    ap.add_argument(
        "--ignore-param",
        action="append",
        default=[],
        help="Ignore a JMH param key when matching entries (repeatable).",
    )

    args = ap.parse_args()

    current_path = os.path.abspath(args.current)
    baseline_path = os.path.abspath(args.baseline)

    return compare(
        current_path=current_path,
        baseline_path=baseline_path,
        benchmark_contains=args.benchmark_contains,
        ignore_params=args.ignore_param,
    )


if __name__ == "__main__":
    raise SystemExit(main())

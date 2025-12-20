#!/usr/bin/env python3
"""Render JMH JSON results into readable tables.

Usage:
  python3 scripts/bench_table.py [path/to/results.json]

Defaults to benchmarks/build/results/jmh/results.json.
"""

from __future__ import annotations

import json
from itertools import groupby
from pathlib import Path
from typing import Any


DEFAULT_RESULTS = Path("benchmarks/build/results/jmh/results.json")


def _fmt_num(value: Any) -> str:
    if value is None:
        return ""
    try:
        return f"{float(value):.3f}"
    except Exception:
        return str(value)


def _render_group(full_benchmark: str, group: list[dict[str, Any]]) -> str:
    cols = ["Impl", "Mean", "p50", "p99", "p99.9", "Unit"]
    num_cols = {"Mean", "p50", "p99", "p99.9"}

    def cell(col: str, row: dict[str, Any]) -> str:
        v = row.get(col)
        return _fmt_num(v) if col in num_cols else str(v or "")

    widths = {c: max(len(c), max(len(cell(c, r)) for r in group)) for c in cols}

    lines = [full_benchmark]
    lines.append("  " + "  ".join(c.ljust(widths[c]) for c in cols))
    lines.append("  " + "  ".join("-" * widths[c] for c in cols))
    for r in group:
        parts: list[str] = []
        for c in cols:
            s = cell(c, r)
            parts.append(s.rjust(widths[c]) if c in num_cols else s.ljust(widths[c]))
        lines.append("  " + "  ".join(parts))
    return "\n".join(lines)


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Print JMH results.json as tables")
    parser.add_argument(
        "path",
        nargs="?",
        default=str(DEFAULT_RESULTS),
        help="Path to JMH results.json (default: benchmarks/build/results/jmh/results.json)",
    )
    args = parser.parse_args()

    path = Path(args.path)
    data = json.loads(path.read_text())

    rows: list[dict[str, Any]] = []
    for entry in data:
        bench = entry.get("benchmark", "")
        params = entry.get("params") or {}
        impl = params.get("implementation", "")

        metric = entry.get("primaryMetric") or {}
        pct = metric.get("scorePercentiles") or {}

        rows.append(
            {
                "full": bench,
                "Impl": impl,
                "Mean": metric.get("score"),
                "p50": pct.get("50.0"),
                "p99": pct.get("99.0"),
                "p99.9": pct.get("99.9"),
                "Unit": metric.get("scoreUnit", ""),
            }
        )

    rows.sort(key=lambda r: (r["full"], r["Impl"]))

    out: list[str] = []
    for full, group_it in groupby(rows, key=lambda r: r["full"]):
        group = list(group_it)
        out.append(_render_group(full, group))
        out.append("")

    print("\n".join(out).rstrip())


if __name__ == "__main__":
    main()

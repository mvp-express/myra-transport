# AGENTS.md

## Purpose

Agent harness for `/home/ubuntu/mvp-express/myra-transport`.

Repository knowledge map: `docs/INDEX.md`.

Workflow guidance: `docs/WORKFLOW.md`.

## Rules

- Treat source and build files as authoritative; docs may be stale.
- Preserve low-latency and low-allocation transport behavior.
- Keep transport API, server adapter, framing, lifecycle, memory, and tests aligned.
- Prefer public transport/runtime tests before implementation changes.
- Document platform-specific behavior instead of hiding it.

## Verification

Run:

```bash
./tools/harness/check.sh
./gradlew check
```

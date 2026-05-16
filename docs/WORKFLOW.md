# Workflow Guidance

## Start

1. Read `AGENTS.md`.
2. Use `docs/INDEX.md`.
3. Check `git status --short`.
4. Validate docs against `settings.gradle.kts`, `build.gradle.kts`, and source.

## Implementation

- Add or update one public transport behavior test first.
- Prefer tests around `Transport`, framing, server behavior, lifecycle, and buffer ownership.
- Keep NIO fallback behavior explicit when touching io_uring paths.
- Preserve buffer lifetime and shutdown semantics.

## Validation

```bash
./tools/harness/check.sh
./gradlew check
```

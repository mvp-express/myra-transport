# Quality Harness

Required checks:

```bash
./tools/harness/check.sh
./gradlew check
```

Current harness coverage:
- required docs exist
- expected Gradle modules are present
- transport/server package roots are present
- `AGENTS.md` maps to durable docs

Future checks:
- README Java version drift check
- io_uring platform capability smoke check
- benchmark result freshness check

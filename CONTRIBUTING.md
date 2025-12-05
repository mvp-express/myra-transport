# Contributing to Myra Transport

Thank you for your interest in contributing to Myra Transport! This document covers development setup, build commands, and contribution guidelines.

## Development Setup

### Prerequisites

- **JDK 25+** (the project uses Java 25 toolchain)
- **Gradle 8.5+** (wrapper included)
- **Linux 5.1+** required for io_uring features (Ubuntu 22.04+ recommended)

### IDE Configuration

For IntelliJ IDEA:

1. Import as Gradle project
2. Set Project SDK to JDK 25
3. Enable annotation processing
4. Add VM options to run configurations:

```text
--enable-preview --enable-native-access=ALL-UNNAMED
```

For VS Code:

1. Install "Extension Pack for Java"
2. Configure `java.configuration.runtimes` to include JDK 25
3. Add launch configuration with VM args for preview features

---

## Gradle Commands

### Building

```bash
# Full build (compile + test + check)
./gradlew build

# Compile only (no tests)
./gradlew assemble

# Clean build artifacts
./gradlew clean

# Clean and rebuild
./gradlew clean build

# Build specific subproject
./gradlew :lib:build
./gradlew :myra-server:build
```

### Testing

```bash
# Run all tests
./gradlew test

# Run tests with verbose output
./gradlew test --info

# Run a specific test class
./gradlew test --tests "express.mvp.myra.transport.IoUringTransportTest"

# Run a specific test method
./gradlew test --tests "express.mvp.myra.transport.IoUringTransportTest.testBasicSendReceive"

# Run tests matching a pattern
./gradlew test --tests "*Connection*"

# Run tests in a specific subproject
./gradlew :lib:test
./gradlew :myra-server:test

# Run tests and show standard output
./gradlew test --info --console=plain

# Continue running tests after failures
./gradlew test --continue

# Re-run tests (ignore up-to-date checks)
./gradlew test --rerun-tasks

# Skip io_uring tests on non-Linux systems
./gradlew test -PskipIoUringTests=true
```

### Code Quality

```bash
# Run Checkstyle
./gradlew checkstyleMain checkstyleTest

# Run SpotBugs (if configured)
./gradlew spotbugsMain

# Run all checks
./gradlew check
```

### Documentation

```bash
# Generate Javadoc
./gradlew javadoc

# Generate aggregated Javadoc (all subprojects)
./gradlew aggregateJavadoc

# Output location: build/docs/javadoc/
```

### Benchmarks

```bash
# Run JMH benchmarks
./gradlew :benchmarks:jmh

# Run specific benchmark
./gradlew :benchmarks:jmh -Pjmh.includes="ThroughputBenchmark"

# Run latency benchmarks
./gradlew :benchmarks:jmh -Pjmh.includes="LatencyBenchmark"

# Run with specific iterations
./gradlew :benchmarks:jmh -Pjmh.warmupIterations=5 -Pjmh.iterations=10

# Run with GC profiler (verify zero-allocation)
./gradlew :benchmarks:jmh -Pjmh.profilers="gc"

# Run with async profiler (flamegraph)
./gradlew :benchmarks:jmh -Pjmh.profilers="async:output=flamegraph"

# Run with perf profiler (Linux only)
./gradlew :benchmarks:jmh -Pjmh.profilers="perf"

# Compare io_uring vs NIO
./gradlew :benchmarks:jmh -Pjmh.includes="TransportComparison"

# Output location: benchmarks/build/results/jmh/
```

### Running Examples

```bash
# Run echo server example
./gradlew :examples:runEchoServer

# Run echo client example
./gradlew :examples:runEchoClient

# Run with custom port
./gradlew :examples:runEchoServer -Pport=9090
```

### Dependencies

```bash
# Show dependency tree
./gradlew dependencies

# Show dependencies for a specific configuration
./gradlew dependencies --configuration runtimeClasspath

# Check for dependency updates
./gradlew dependencyUpdates
```

### Publishing (Maintainers)

```bash
# Publish to local Maven repository
./gradlew publishToMavenLocal

# Publish to remote repository (requires credentials)
./gradlew publish
```

### Useful Combinations

```bash
# Quick validation before committing
./gradlew clean check

# Full CI build
./gradlew clean build javadoc

# Fast iteration during development
./gradlew assemble test --tests "*YourTest*"

# Run benchmarks and save results
./gradlew :benchmarks:jmh && cp benchmarks/build/results/jmh/results.json benchmarks/results/
```

---

## Common Issues

### FFM Access Errors

If you see `java.lang.IllegalCallerException`:

```text
Add to your JVM args: --enable-native-access=ALL-UNNAMED
```

### Preview Feature Errors

If you see `preview features are not enabled`:

```text
Add to your JVM args: --enable-preview
```

### io_uring Not Available

If io_uring tests fail on your system:

```bash
# Check kernel version (need 5.1+)
uname -r

# Check io_uring support
cat /proc/kallsyms | grep io_uring

# Skip io_uring tests
./gradlew test -PskipIoUringTests=true
```

### Permission Denied for io_uring

If you see permission errors:

```bash
# Check ulimits
ulimit -l

# May need to increase locked memory limit
sudo sh -c 'echo "* soft memlock unlimited" >> /etc/security/limits.conf'
sudo sh -c 'echo "* hard memlock unlimited" >> /etc/security/limits.conf'
```

---

## Pull Request Process

1. **Fork** the repository and create a feature branch
2. **Write tests** for new functionality
3. **Run** `./gradlew clean check` before submitting
4. **Sign** the [CLA](CLA.md) if you haven't already
5. **Submit** PR with clear description of changes

### Commit Message Format

```text
component: Short summary (50 chars or less)

Longer description if needed. Wrap at 72 characters.
Explain what and why, not how.

Fixes #123
```

### Code Style

- Follow existing code conventions
- Use meaningful variable names
- Add Javadoc for public APIs
- Keep methods focused and small

---

## Questions?

Open a [GitHub Discussion](https://github.com/mvp-express/myra-transport/discussions) for questions or ideas.

# MyraTransport

> Ultra-low-latency networking with io_uring and Java's FFM API.

[![Build](https://img.shields.io/github/actions/workflow/status/mvp-express/myra-transport/build.yml?branch=main)](https://github.com/mvp-express/myra-transport/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Installation

```kotlin
dependencies {
    implementation("express.mvp.myra.transport:myra-transport:0.1.0")
}
```

**Requires Java 24+** with FFM enabled and Linux 5.1+ for io_uring:

```bash
java --enable-native-access=ALL-UNNAMED -jar your-app.jar
```

## Quick Example

```java
// Server with io_uring backend
var config = TransportConfig.builder()
    .backend(Backend.IO_URING)
    .port(8080)
    .build();

var server = new MyraServer(config);
server.onMessage((ctx, segment) -> {
    // Zero-copy message handling
    ctx.reply(responseSegment);
});
server.start();
```

## Performance

| Metric | MyraTransport | Netty |
|--------|---------------|-------|
| p99 Latency | 12μs | 45μs |
| Throughput | 2.1M msg/s | 850K msg/s |
| GC Pressure | Zero | Medium |

## Documentation

📚 **[User Guide](https://mvp.express/docs/myra-transport/)** — Full documentation  
🚀 **[Getting Started](https://mvp.express/docs/getting-started/)** — Ecosystem tutorial  
📖 **[API Reference](https://mvp.express/docs/myra-transport/api/)** — Javadoc  
📊 **[Benchmarks](https://mvp.express/benchmarks/)** — Performance analysis

## For Contributors

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and PR process.

## License

Apache 2.0 — See [LICENSE](LICENSE)

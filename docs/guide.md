# MyraTransport Usage Guide

This document reflects the current APIs in `myra-transport` as of November 2025. Older snippets (single-argument `TransportFactory.create`, TLS builders, custom batching configs, etc.) no longer match the codebase and have been removed or updated.

## Quick Start

### Dependencies

```gradle
dependencies {
    implementation("express.mvp.myra.transport:myra-transport:0.1.0-SNAPSHOT")
    implementation(files("libs/roray-ffm-utils-0.1.0-SNAPSHOT.jar"))
}
```

### Minimal TCP Client

```java
import express.mvp.myra.transport.*;

import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

TransportConfig config = TransportConfig.builder()
    .backendType(TransportConfig.BackendType.IO_URING)
    .registeredBuffers(TransportConfig.RegisteredBuffersConfig.builder()
        .enabled(true)
        .numBuffers(256)
        .bufferSize(64 * 1024)
        .build())
    .build();

SocketAddress address = new InetSocketAddress("localhost", 9000);
Transport transport = TransportFactory.create(config, address);

CompletableFuture<Void> connected = transport.connect(address);
connected.join();

try (RegisteredBuffer buffer = transport.acquireBuffer()) {
    byte[] payload = "ping".getBytes(StandardCharsets.UTF_8);
    MemorySegment segment = buffer.segment();
    MemorySegment payloadSegment = MemorySegment.ofArray(payload);
    MemorySegment.copy(payloadSegment, 0, segment, 0, payload.length);

    transport.send(segment.asSlice(0, payload.length)).join();
    MemorySegment reply = transport.receive().join();
    String replyText = StandardCharsets.UTF_8.decode(reply.asByteBuffer()).toString();
    System.out.println("Server replied " + replyText);
}

transport.close();
```

Key takeaways:
- Always supply the remote `SocketAddress` when calling `TransportFactory.create`.
- Acquire buffers via `transport.acquireBuffer()` and close them (or use try-with-resources) to return them to the pool.
- `send`, `receive`, and `request` return `CompletableFuture`s.

## Configuration Essentials

`TransportConfig` currently exposes three knobs:

```java
TransportConfig config = TransportConfig.builder()
    .backendType(TransportConfig.BackendType.NIO) // or IO_URING
    .registeredBuffers(TransportConfig.RegisteredBuffersConfig.builder()
        .enabled(true)
        .numBuffers(128)
        .bufferSize(32 * 1024)
        .build())
    .connectionTimeout(Duration.ofSeconds(10))
    .build();
```

- **backendType**: selects `IO_URING` (default) or `NIO`. `XDP` and `DPDK` enums exist for future work but currently throw `UnsupportedOperationException`.
- **registeredBuffers**: controls pool size and per-buffer capacity. Disabling registered buffers throws during factory creation because every backend expects them today.
- **connectionTimeout**: passed through to backends when establishing new connections.

## Factory & Connection Pooling

`TransportFactory` centralizes object creation:

```java
SocketAddress remote = new InetSocketAddress("demo.example", 7001);
Transport transport = TransportFactory.create(config, remote);

ConnectionPool pool = TransportFactory.createPool(config);
Transport pooled = pool.acquire(remote).join();
// ... use pooled transport ...
pool.release(pooled);
pool.close();
```

- `TransportFactory.create(config, remote)` wires up a `TcpTransport`, backend, and registered buffer pool for a single endpoint.
- `TransportFactory.createPool(config)` returns `ConnectionPoolImpl`, which lazily spins up transports per address and reuses them. Pool limits default to 16 connections per host.

## Core Interfaces Snapshot

### Transport

```java
public interface Transport extends AutoCloseable {
    CompletableFuture<Void> connect(SocketAddress remote);
    CompletableFuture<Void> send(MemorySegment data);
    CompletableFuture<MemorySegment> receive();
    default CompletableFuture<MemorySegment> request(MemorySegment req) { ... }
    Flow.Publisher<MemorySegment> stream(MemorySegment initial); // not yet implemented
    RegisteredBuffer acquireBuffer();
    int availableBufferSpace();
    ConnectionPool getConnectionPool(); // null for direct TcpTransport
    TransportHealth getHealth();
    boolean isConnected();
    SocketAddress getRemoteAddress();
    void close();
}
```

### RegisteredBuffer & Pool

- `RegisteredBuffer` behaves like a fixed-size byte buffer backed by a `MemorySegment`.
- `RegisteredBufferPoolImpl` pre-allocates aligned off-heap segments and hands out `RegisteredBuffer` views. Calling `close()` on a buffer returns it to the pool.

### TransportBackend

Backends abstract system-specific I/O. The stock implementations are:
- `express.mvp.myra.transport.iouring.IoUringBackend`
- `express.mvp.myra.transport.nio.NioBackend`

Both expose `send`, `receive`, `connect`, batching hooks, and reporting via `BackendStats`.

## Working with Registered Buffers

```java
try (RegisteredBuffer buffer = transport.acquireBuffer()) {
    MemorySegment segment = buffer.segment();
    String payload = "example";
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);
    transport.send(segment.asSlice(0, bytes.length)).join();
}
```

Tips:
- Call `buffer.clear()` before reusing manually.
- Check `transport.availableBufferSpace()` before starting bursty workloads to avoid pool exhaustion.
- Never stash `MemorySegment` references after closing the buffer; the underlying memory is recycled.

## Connection Pool Example

```java
SocketAddress marketData = new InetSocketAddress("md-feed", 14000);
ConnectionPool pool = TransportFactory.createPool(config);

pool.acquire(marketData, new ConnectionHandler() {
    @Override
    public void onConnectionAcquired(Transport connection, long token) {
        try (RegisteredBuffer buffer = connection.acquireBuffer()) {
            encodeSnapshot(buffer.segment());
            connection.send(buffer.segment().asSlice(0, SNAPSHOT_SIZE));
        }
    }
    
    @Override
    public void onConnectionFailed(Throwable cause, long token) {
        System.err.println("Failed to connect: " + cause);
    }
});
```

Internally the pool maintains a semaphore per endpoint and automatically spins up new transports (with their own buffer pools) when capacity allows.

## Backend Selection

| Backend | Status | Notes |
|---------|--------|-------|
| `IO_URING` | ✅ | Requires Linux 5.1+, fastest path, registered buffers mandatory |
| `NIO` | ✅ | Works everywhere, lower throughput, ignores registered buffers |
| `XDP` / `DPDK` | planned | Enum values exist but `TransportFactory` throws `UnsupportedOperationException` |

Switch backends via `TransportConfig.Builder.backendType`.

## Monitoring & Health

`TcpTransport.getHealth()` returns a `TransportHealth` snapshot:

```java
TransportHealth health = transport.getHealth();
if (!health.isHealthy()) {
    System.err.println("Transport unhealthy: " + health.getErrorMessage());
}
```

Backends also expose `BackendStats` (operation counts, errors) and methods such as `submitBatch()` / `poll()` for event-loop integrations.

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| `IllegalArgumentException: Registered buffers must be enabled` | `RegisteredBuffersConfig.enabled(false)` | Leave buffers enabled (current backends assume them) |
| Connection hangs indefinitely with NIO backend | Network connectivity issue or backend bug (fixed in latest version) | Ensure you're using the latest version; connection should timeout within 5-7 seconds |
| `Connection timeout after 5000ms` | Remote server not reachable or not listening | Verify server is running and accessible; check firewall rules |
| `Connection pool exhausted` | More concurrent borrows than `maxConnectionsPerHost` | Release transports promptly or raise the permit count inside `ConnectionPoolImpl` |
| `UnsupportedOperationException: IO_URING backend not yet implemented` | Using `XDP/DPDK` types | Stick to IO_URING or NIO for now |
| `UnsatisfiedLinkError` during `IoUringBackend` init | Missing liburing | Install `liburing` or let factory fall back to NIO |

## Complete Echo Example

```java
import java.nio.charset.StandardCharsets;

public final class EchoClient {
    public static void main(String[] args) throws Exception {
        TransportConfig config = TransportConfig.builder()
            .backendType(TransportConfig.BackendType.IO_URING)
            .registeredBuffers(TransportConfig.RegisteredBuffersConfig.defaults())
            .build();

        SocketAddress address = new InetSocketAddress("localhost", 9000);

        try (Transport transport = TransportFactory.create(config, address)) {
            transport.connect(address).join();

            try (RegisteredBuffer buffer = transport.acquireBuffer()) {
                String msg = "Hello, World!";
                byte[] msgBytes = msg.getBytes(StandardCharsets.UTF_8);
                MemorySegment.copy(MemorySegment.ofArray(msgBytes), 0, buffer.segment(), 0, msgBytes.length);

                MemorySegment slice = buffer.segment().asSlice(0, msgBytes.length);
                transport.send(slice).join();
                MemorySegment echoedSegment = transport.receive().join();
                String echoed = StandardCharsets.UTF_8.decode(echoedSegment.asByteBuffer()).toString();
                System.out.println("Received: " + echoed);
            }
        }
    }
}
```

---

For additional API docs, generate Javadoc (`./gradlew :lib:javadoc`) and open `lib/build/docs/javadoc/index.html`.

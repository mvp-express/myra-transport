package express.mvp.myra.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Integration tests for complete transport stack. */
class TransportIntegrationTest {

    private Transport transport;
    private ConnectionPool pool;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void testTransportCreation() {
        System.out.println("[TEST] testTransportCreation - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating address...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        System.out.println("[TEST] Address created: " + addr);

        System.out.println("[TEST] Creating transport...");
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Running assertions...");
        assertNotNull(transport);
        assertFalse(transport.isConnected());
        assertEquals(addr, transport.getRemoteAddress());
        System.out.println("[TEST] testTransportCreation - PASS");
    }

    @Test
    void testBufferAcquisition() {
        System.out.println("[TEST] testBufferAcquisition - START");
        System.out.println("[TEST] Creating config with registered buffers...");
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.NIO)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating transport...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Acquiring buffer...");
        try (RegisteredBuffer buffer = transport.acquireBuffer()) {
            System.out.println("[TEST] Buffer acquired");
            assertNotNull(buffer);
            assertEquals(4096, buffer.capacity());

            System.out.println("[TEST] Writing to buffer...");
            MemorySegment segment = buffer.segment();
            segment.set(ValueLayout.JAVA_INT, 0, 42);
            assertEquals(42, segment.get(ValueLayout.JAVA_INT, 0));
            System.out.println("[TEST] Buffer operations complete");
        }
        System.out.println("[TEST] testBufferAcquisition - PASS");
    }

    @Test
    void testConnectionPoolCreation() {
        System.out.println("[TEST] testConnectionPoolCreation - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating connection pool...");
        pool = TransportFactory.createPool(config);
        System.out.println("[TEST] Pool created");

        System.out.println("[TEST] Checking pool state...");
        assertNotNull(pool);
        assertEquals(0, pool.getActiveConnectionCount());
        assertEquals(0, pool.getIdleConnectionCount());
        System.out.println("[TEST] testConnectionPoolCreation - PASS");
    }

    @Test
    void testTransportHealth() {
        System.out.println("[TEST] testTransportHealth - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating transport...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Getting health status...");
        TransportHealth health = transport.getHealth();
        System.out.println("[TEST] Health retrieved");

        assertNotNull(health);
        assertFalse(health.isHealthy()); // Not connected yet
        System.out.println("[TEST] testTransportHealth - PASS");
    }

    @Test
    void testAvailableBufferSpace() {
        System.out.println("[TEST] testAvailableBufferSpace - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.NIO)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .numBuffers(10)
                                        .build())
                        .build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating transport...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Checking initial buffer space...");
        assertEquals(10, transport.availableBufferSpace());
        System.out.println("[TEST] Initial space: 10");

        System.out.println("[TEST] Acquiring buffer...");
        RegisteredBuffer buffer = transport.acquireBuffer();
        System.out.println("[TEST] Buffer acquired");
        assertEquals(9, transport.availableBufferSpace());
        System.out.println("[TEST] Space after acquire: 9");

        System.out.println("[TEST] Releasing buffer...");
        buffer.close();
        System.out.println("[TEST] Buffer released");
        assertEquals(10, transport.availableBufferSpace());
        System.out.println("[TEST] testAvailableBufferSpace - PASS");
    }

    @Test
    void testSendWithoutConnectionFails() {
        System.out.println("[TEST] testSendWithoutConnectionFails - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating transport...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Acquiring buffer and attempting send...");
        try (RegisteredBuffer buffer = transport.acquireBuffer()) {
            MemorySegment segment = buffer.segment();

            System.out.println("[TEST] Calling send (should fail)...");
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        transport.send(segment);
                    });
            System.out.println("[TEST] Send correctly failed");
        }
        System.out.println("[TEST] testSendWithoutConnectionFails - PASS");
    }

    @Test
    void testConnectFailsWithoutServer() {
        System.out.println("[TEST] testConnectFailsWithoutServer - START");
        System.out.println("[TEST] Creating config...");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();
        System.out.println("[TEST] Config created");

        System.out.println("[TEST] Creating transport...");
        SocketAddress addr = new InetSocketAddress("localhost", 9000);
        transport = TransportFactory.create(config, addr);
        System.out.println("[TEST] Transport created");

        System.out.println("[TEST] Attempting connect (should timeout/fail)...");

        java.util.concurrent.CompletableFuture<Void> future =
                new java.util.concurrent.CompletableFuture<>();

        transport.start(
                new TransportHandlerAdapter() {
                    @Override
                    public void onConnected(long token) {
                        future.completeExceptionally(new RuntimeException("Should not connect"));
                    }

                    @Override
                    public void onConnectionFailed(long token, Throwable cause) {
                        future.complete(null);
                    }
                });

        transport.connect(addr);

        try {
            future.get(6, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("[TEST] Connection failed as expected");
        } catch (Exception e) {
            fail("Connect should have failed gracefully via callback, but threw: " + e);
        }
        System.out.println("[TEST] testConnectFailsWithoutServer - PASS");
    }

    @Test
    void testConnectionPoolAcquire() {
        System.out.println("[TEST] testConnectionPoolAcquire - START");
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();

        pool = TransportFactory.createPool(config);
        SocketAddress addr = new InetSocketAddress("localhost", 9000);

        java.util.concurrent.CompletableFuture<Void> future =
                new java.util.concurrent.CompletableFuture<>();

        pool.acquire(
                addr,
                new ConnectionHandler() {
                    @Override
                    public void onConnectionAcquired(Transport transport, long token) {
                        future.completeExceptionally(
                                new RuntimeException("Should not connect without server"));
                    }

                    @Override
                    public void onConnectionFailed(Throwable cause, long token) {
                        future.complete(null);
                    }
                });

        try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("[TEST] Connection failed as expected");
        } catch (Exception e) {
            fail("Should have failed gracefully: " + e);
        }
        System.out.println("[TEST] testConnectionPoolAcquire - PASS");
    }
}

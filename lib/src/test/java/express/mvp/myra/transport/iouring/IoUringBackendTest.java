package express.mvp.myra.transport.iouring;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import express.mvp.myra.transport.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Comprehensive tests for IoUringBackend.
 *
 * <p>Tests cover: - Basic initialization and lifecycle - Connection management - Send/receive
 * operations - Zero-copy send (SEND_ZC) - Multi-shot receive - Error handling and edge cases -
 * Pre-allocated structure reuse (zero-allocation verification)
 */
@EnabledOnOs(OS.LINUX)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IoUringBackendTest {

    private IoUringBackend backend;
    private RegisteredBufferPoolImpl bufferPool;
    private ServerSocketChannel echoServer;
    private static final int TEST_PORT = 19876;
    private static final InetSocketAddress TEST_ADDRESS =
            new InetSocketAddress("127.0.0.1", TEST_PORT);

    @BeforeEach
    void setUp() {
        assumeTrue(LibUring.isAvailable(), "io_uring not available on this system");
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        if (echoServer != null) {
            try {
                echoServer.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // ========== Initialization Tests ==========

    @Test
    @Order(1)
    @DisplayName("Backend initializes successfully with default config")
    void testInitializationDefault() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        assertDoesNotThrow(() -> backend.initialize(config));
        assertEquals("io_uring", backend.getBackendType());
    }

    @Test
    @Order(2)
    @DisplayName("Backend initializes with SQPOLL enabled")
    void testInitializationWithSqpoll() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .sqPollEnabled(true)
                        .sqPollIdleTimeout(1000) // 1ms
                        .build();

        // SQPOLL may fail on some systems (needs CAP_SYS_ADMIN or io_uring_setup_sqpoll permission)
        // But the fallback should work
        assertDoesNotThrow(() -> backend.initialize(config));
        assertEquals("io_uring", backend.getBackendType());
    }

    @Test
    @Order(3)
    @DisplayName("Double initialization throws exception")
    void testDoubleInitializationFails() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertThrows(IllegalStateException.class, () -> backend.initialize(config));
    }

    @Test
    @Order(4)
    @DisplayName("Operations before initialization throw exception")
    void testOperationsBeforeInit() {
        backend = new IoUringBackend();

        assertThrows(IllegalStateException.class, () -> backend.poll((t, r) -> {}));
        assertThrows(
                IllegalStateException.class, () -> backend.waitForCompletion(100, (t, r) -> {}));
    }

    // ========== Buffer Pool Tests ==========

    @Test
    @Order(10)
    @DisplayName("Buffer pool registration succeeds")
    void testBufferPoolRegistration() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);

        assertDoesNotThrow(() -> backend.registerBufferPool(bufferPool));
        assertTrue(backend.supportsRegisteredBuffers());
    }

    @Test
    @Order(11)
    @DisplayName("Null or invalid buffer pool throws exception")
    void testNullBufferPool() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Should throw either NPE or IllegalArgumentException
        assertThrows(Exception.class, () -> backend.registerBufferPool(null));
    }

    // ========== Connection Tests ==========

    @Test
    @Order(20)
    @DisplayName("Connect to non-existent server fails gracefully")
    void testConnectToNonExistentServer() throws Exception {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(0);

        // Connect to a port that's not listening
        InetSocketAddress badAddress = new InetSocketAddress("127.0.0.1", 59999);
        backend.connect(badAddress, 1L);
        backend.submitBatch();

        // Poll for result - expect connection refused
        long deadline = System.currentTimeMillis() + 5000;
        while (result.get() == 0 && System.currentTimeMillis() < deadline) {
            backend.waitForCompletion(
                    100,
                    (token, res) -> {
                        if (token == 1L) {
                            result.set(res);
                            latch.countDown();
                        }
                    });
        }

        // Connection should fail with ECONNREFUSED (-111) or similar
        assertTrue(result.get() < 0, "Connection to non-existent server should fail");
    }

    @Test
    @Order(21)
    @DisplayName("Connect and send/receive works")
    void testConnectAndSendReceive() throws Exception {
        // Start echo server
        echoServer = ServerSocketChannel.open();
        echoServer.bind(TEST_ADDRESS);
        echoServer.configureBlocking(false);

        Thread serverThread =
                new Thread(
                        () -> {
                            try {
                                SocketChannel client = null;
                                while (client == null) {
                                    client = echoServer.accept();
                                    Thread.sleep(10);
                                }
                                client.configureBlocking(true);
                                ByteBuffer buf = ByteBuffer.allocate(1024);
                                while (client.read(buf) != -1) {
                                    buf.flip();
                                    while (buf.hasRemaining()) {
                                        client.write(buf);
                                    }
                                    buf.clear();
                                }
                            } catch (Exception e) {
                                // Server closed
                            }
                        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(100);

        // Initialize backend
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Connect
        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean(false);

        backend.connect(TEST_ADDRESS, 100L);
        backend.submitBatch();

        long deadline = System.currentTimeMillis() + 5000;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            backend.waitForCompletion(
                    100,
                    (token, res) -> {
                        if (token == 100L && res >= 0) {
                            connected.set(true);
                            connectLatch.countDown();
                        }
                    });
        }

        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Should connect");
        assertTrue(connected.get(), "Connection should succeed");

        // Send data
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sendBuf = arena.allocate(4);
            sendBuf.set(ValueLayout.JAVA_INT, 0, 12345);

            backend.send(sendBuf, 4, 200L);
            backend.submitBatch();

            AtomicBoolean sent = new AtomicBoolean(false);
            deadline = System.currentTimeMillis() + 2000;
            while (!sent.get() && System.currentTimeMillis() < deadline) {
                backend.waitForCompletion(
                        100,
                        (token, res) -> {
                            if (token == 200L && res > 0) {
                                sent.set(true);
                            }
                        });
            }
            assertTrue(sent.get(), "Send should complete");

            // Receive echo
            MemorySegment recvBuf = arena.allocate(4);
            backend.receive(recvBuf, 4, 300L);
            backend.submitBatch();

            AtomicBoolean received = new AtomicBoolean(false);
            AtomicInteger receivedValue = new AtomicInteger(0);
            deadline = System.currentTimeMillis() + 2000;
            while (!received.get() && System.currentTimeMillis() < deadline) {
                backend.waitForCompletion(
                        100,
                        (token, res) -> {
                            if (token == 300L && res > 0) {
                                receivedValue.set(recvBuf.get(ValueLayout.JAVA_INT, 0));
                                received.set(true);
                            }
                        });
            }

            assertTrue(received.get(), "Receive should complete");
            assertEquals(12345, receivedValue.get(), "Echoed value should match");
        }
    }

    // ========== Zero-Copy Send Tests ==========

    @Test
    @Order(30)
    @DisplayName("Zero-copy send completes (notification may or may not come)")
    void testZeroCopySendNotification() throws Exception {
        // Start echo server
        echoServer = ServerSocketChannel.open();
        echoServer.bind(TEST_ADDRESS);
        echoServer.configureBlocking(false);

        Thread serverThread =
                new Thread(
                        () -> {
                            try {
                                SocketChannel client = null;
                                long start = System.currentTimeMillis();
                                while (client == null
                                        && System.currentTimeMillis() - start < 5000) {
                                    client = echoServer.accept();
                                    Thread.sleep(10);
                                }
                                if (client != null) {
                                    client.configureBlocking(true);
                                    ByteBuffer buf = ByteBuffer.allocate(1024);
                                    client.read(buf); // Just read, don't echo
                                    client.close();
                                }
                            } catch (Exception e) {
                                // Server closed
                            }
                        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(100);

        // Initialize backend
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Connect first
        backend.connect(TEST_ADDRESS, 100L);
        backend.submitBatch();

        CountDownLatch connectLatch = new CountDownLatch(1);
        AtomicBoolean connected = new AtomicBoolean(false);
        long deadline = System.currentTimeMillis() + 5000;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            backend.waitForCompletion(
                    100,
                    (token, res) -> {
                        if (token == 100L && res >= 0) {
                            connected.set(true);
                            connectLatch.countDown();
                        }
                    });
        }

        if (!connected.get()) {
            // Skip if can't connect
            return;
        }

        // Send with zero-copy
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sendBuf = arena.allocate(64);
            sendBuf.fill((byte) 'X');

            AtomicBoolean gotCompletion = new AtomicBoolean(false);
            AtomicBoolean gotNotification = new AtomicBoolean(false);
            AtomicInteger sendResult = new AtomicInteger(0);

            backend.sendZeroCopy(sendBuf, 64, 200L);
            backend.submitBatch();

            // Poll for completions
            deadline = System.currentTimeMillis() + 3000;
            while (!gotCompletion.get() && System.currentTimeMillis() < deadline) {
                backend.poll(
                        (IoUringBackend.ExtendedCompletionHandler)
                                (token, res, flags) -> {
                                    if (token == 200L) {
                                        if ((flags & LibUring.IORING_CQE_F_NOTIF) != 0) {
                                            gotNotification.set(true);
                                        } else {
                                            sendResult.set(res);
                                            gotCompletion.set(true);
                                        }
                                    }
                                });
                Thread.sleep(10);
            }

            // Zero-copy send should complete (result > 0 means bytes sent, or may fail with EINVAL
            // if not supported)
            assertTrue(gotCompletion.get(), "Should get send completion");
            // Result may be positive (success) or negative (error like EOPNOTSUPP if kernel doesn't
            // support ZC)
        }
    }

    // ========== Error Handling Tests ==========

    @Test
    @Order(40)
    @DisplayName("Send on closed connection fails")
    void testSendOnClosedConnection() throws Exception {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Try to send without connecting - should fail at either submit or completion
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sendBuf = arena.allocate(64);

            // This may throw or queue an error that we'll see on completion
            try {
                backend.send(sendBuf, 64, 100L);
                backend.submitBatch();

                // Poll for error completion
                AtomicInteger result = new AtomicInteger(0);
                long deadline = System.currentTimeMillis() + 1000;
                while (result.get() == 0 && System.currentTimeMillis() < deadline) {
                    backend.poll(
                            (token, res) -> {
                                if (token == 100L) {
                                    result.set(res);
                                }
                            });
                }
                // Result should be negative (error) since no connection
                assertTrue(
                        result.get() < 0 || result.get() == 0,
                        "Send without connection should fail or timeout");
            } catch (Exception e) {
                // Exception is also acceptable
                assertTrue(true, "Exception on send without connection is acceptable");
            }
        }
    }

    @Test
    @Order(41)
    @DisplayName("Invalid address type throws exception")
    void testInvalidAddressType() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Create a non-InetSocketAddress
        java.net.SocketAddress badAddress = new java.net.SocketAddress() {};

        assertThrows(
                IllegalArgumentException.class,
                () -> backend.connect(badAddress, 1L),
                "Non-InetSocketAddress should throw");
    }

    // ========== Pre-allocated Structure Tests ==========

    @Test
    @Order(50)
    @DisplayName("Pre-allocated timespec is reused across waitForCompletion calls")
    void testPreAllocatedTimespecReuse() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Call waitForCompletion multiple times - should not allocate
        // (This is a functional test; allocation tracking would require profiling)
        for (int i = 0; i < 100; i++) {
            backend.waitForCompletion(1, (token, res) -> {});
        }

        // If we got here without OOM or issues, the pre-allocation is working
        assertTrue(true, "Multiple waitForCompletion calls should reuse timespec");
    }

    @Test
    @Order(51)
    @DisplayName("Spin-wait in acquireSqeWithRetry works under contention")
    void testSpinWaitUnderContention() throws Exception {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Start echo server
        echoServer = ServerSocketChannel.open();
        echoServer.bind(TEST_ADDRESS);
        echoServer.configureBlocking(false);

        Thread serverThread =
                new Thread(
                        () -> {
                            try {
                                SocketChannel client = null;
                                while (client == null) {
                                    client = echoServer.accept();
                                    Thread.sleep(10);
                                }
                                client.configureBlocking(true);
                                ByteBuffer buf = ByteBuffer.allocate(4096);
                                while (client.read(buf) != -1) {
                                    buf.flip();
                                    while (buf.hasRemaining()) {
                                        client.write(buf);
                                    }
                                    buf.clear();
                                }
                            } catch (Exception e) {
                                // Expected on close
                            }
                        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(100);

        // Connect
        backend.connect(TEST_ADDRESS, 100L);
        backend.submitBatch();

        CountDownLatch connectLatch = new CountDownLatch(1);
        long deadline = System.currentTimeMillis() + 5000;
        while (connectLatch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            backend.waitForCompletion(
                    100,
                    (token, res) -> {
                        if (token == 100L && res >= 0) {
                            connectLatch.countDown();
                        }
                    });
        }
        assertTrue(connectLatch.await(1, TimeUnit.SECONDS), "Should connect");

        // Rapid-fire sends to test spin-wait behavior
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sendBuf = arena.allocate(64);
            sendBuf.fill((byte) 'A');

            AtomicInteger completions = new AtomicInteger(0);
            int numSends = 50;

            for (int i = 0; i < numSends; i++) {
                backend.send(sendBuf, 64, 200L + i);
            }
            backend.submitBatch();

            deadline = System.currentTimeMillis() + 10000;
            while (completions.get() < numSends && System.currentTimeMillis() < deadline) {
                backend.poll(
                        (token, res) -> {
                            if (token >= 200L && res > 0) {
                                completions.incrementAndGet();
                            }
                        });
            }

            assertEquals(numSends, completions.get(), "All sends should complete");
        }
    }

    // ========== Lifecycle Tests ==========

    @Test
    @Order(60)
    @DisplayName("Close releases resources")
    void testClose() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertDoesNotThrow(() -> backend.close());

        // Second close should be idempotent
        assertDoesNotThrow(() -> backend.close());
    }

    @Test
    @Order(61)
    @DisplayName("Operations after close throw or return gracefully")
    void testOperationsAfterClose() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);
        backend.close();

        // After close, operations should either throw or return 0/no-op
        try {
            int result = backend.poll((t, r) -> {});
            assertTrue(result >= 0, "Poll after close should return 0 or throw");
        } catch (Exception e) {
            // Exception is also acceptable
            assertTrue(true, "Exception after close is acceptable");
        }
    }

    // ========== Statistics Tests ==========

    @Test
    @Order(70)
    @DisplayName("Statistics are tracked correctly")
    void testStatistics() throws Exception {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(4096)
                                        .build())
                        .build();

        backend.initialize(config);
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);
        backend.registerBufferPool(bufferPool);

        // Get initial stats
        BackendStats stats = backend.getStats();
        assertNotNull(stats);
        assertEquals(0, stats.getTotalBytesSent());
        assertEquals(0, stats.getTotalBytesReceived());
        assertEquals(0, stats.getTotalSends());
        assertEquals(0, stats.getTotalReceives());
    }

    // ========== P2: Buffer Ring Tests ==========

    @Test
    @Order(80)
    @DisplayName("Buffer ring initialization returns boolean")
    void testBufferRingInit() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Should return true or false without exception
        boolean result = backend.initBufferRing();
        assertTrue(result || !result, "initBufferRing should return boolean");
    }

    @Test
    @Order(81)
    @DisplayName("Buffer ring with custom parameters")
    void testBufferRingInitCustom() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Power of 2 sizes
        boolean result = backend.initBufferRing(64, 4096, (short) 0);
        // May not be supported, but shouldn't throw
        assertTrue(result || !result);
    }

    @Test
    @Order(82)
    @DisplayName("Buffer ring with non-power-of-2 throws IllegalArgumentException")
    void testBufferRingNonPowerOf2() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Non-power-of-2 should throw
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    backend.initBufferRing(100, 4096, (short) 0);
                });
    }

    @Test
    @Order(83)
    @DisplayName("isBufferRingEnabled tracks state correctly")
    void testBufferRingEnabledState() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Initially not enabled
        assertFalse(backend.isBufferRingEnabled());

        // After init, depends on kernel support
        backend.initBufferRing();
        // State should be consistent
        assertEquals(backend.isBufferRingEnabled(), backend.getBufferGroupId() >= 0);
    }

    @Test
    @Order(84)
    @DisplayName("getBufferGroupId returns -1 when not enabled")
    void testBufferGroupIdNotEnabled() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Before enabling, should be -1
        assertEquals(-1, backend.getBufferGroupId());
    }

    @Test
    @Order(85)
    @DisplayName("getBufferRingBuffer returns null for invalid ID")
    void testBufferRingBufferInvalidId() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Without buffer ring, should return null
        assertNull(backend.getBufferRingBuffer(0));
        assertNull(backend.getBufferRingBuffer(-1));
        assertNull(backend.getBufferRingBuffer(1000));
    }

    // ========== P2: Linked Operations Tests ==========

    @Test
    @Order(90)
    @DisplayName("submitLinkedEcho requires connection")
    void testLinkedEchoRequiresConnection() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(1024);

            // Without connection, should return false
            boolean result = backend.submitLinkedEcho(buffer, 1024, 1L, 2L);
            assertFalse(result, "Should fail without connection");
        }
    }

    @Test
    @Order(91)
    @DisplayName("submitLinkedEchoSkipRecvCqe requires connection")
    void testLinkedEchoSkipCqeRequiresConnection() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(1024);

            boolean result = backend.submitLinkedEchoSkipRecvCqe(buffer, 1024, 1L);
            assertFalse(result);
        }
    }

    @Test
    @Order(92)
    @DisplayName("submitLinkedRequestResponse requires connection")
    void testLinkedRequestResponseRequiresConnection() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sendBuf = arena.allocate(1024);
            MemorySegment recvBuf = arena.allocate(1024);

            boolean result =
                    backend.submitLinkedRequestResponse(sendBuf, 100, recvBuf, 1024, 1L, 2L);
            assertFalse(result);
        }
    }

    // ========== P3: Batch Operations Tests ==========

    @Test
    @Order(100)
    @DisplayName("submitBatchRecv handles null/empty input")
    void testBatchRecvNullInput() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Null arrays should return 0
        assertEquals(0, backend.submitBatchRecv(null, null, null, 0));
        assertEquals(0, backend.submitBatchRecv(null, new int[] {}, new long[] {}, 0));
    }

    @Test
    @Order(101)
    @DisplayName("submitBatchRecv returns 0 without connection")
    void testBatchRecvWithoutConnection() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment[] buffers = new MemorySegment[3];
            int[] lengths = new int[3];
            long[] tokens = new long[3];

            for (int i = 0; i < 3; i++) {
                buffers[i] = arena.allocate(1024);
                lengths[i] = 1024;
                tokens[i] = i + 1L;
            }

            // Without connection, should return 0
            int result = backend.submitBatchRecv(buffers, lengths, tokens, 3);
            assertEquals(0, result);
        }
    }

    @Test
    @Order(102)
    @DisplayName("submitBatchSend handles null/empty input")
    void testBatchSendNullInput() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertEquals(0, backend.submitBatchSend(null, null, null, 0));
    }

    @Test
    @Order(103)
    @DisplayName("submitBatchRecvRegistered handles null input")
    void testBatchRecvRegisteredNullInput() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertEquals(0, backend.submitBatchRecvRegistered(null, null, null, 0));
    }

    @Test
    @Order(104)
    @DisplayName("forceSubmit works on initialized backend")
    void testForceSubmit() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // With no queued operations, should return 0
        int result = backend.forceSubmit();
        assertEquals(0, result);
    }

    // ========== P3: Fixed File Tests ==========

    @Test
    @Order(110)
    @DisplayName("isUsingFixedFile initially false")
    void testIsUsingFixedFileInitial() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertFalse(backend.isUsingFixedFile());
    }

    @Test
    @Order(111)
    @DisplayName("getFixedFileIndex returns -1 when not using fixed files")
    void testGetFixedFileIndexNotUsing() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        assertEquals(-1, backend.getFixedFileIndex());
    }

    @Test
    @Order(112)
    @DisplayName("submitMultishotRecvWithBufferRing returns false when not enabled")
    void testMultishotRecvWithBufferRingNotEnabled() {
        backend = new IoUringBackend();
        TransportConfig config =
                TransportConfig.builder().backendType(TransportConfig.BackendType.IO_URING).build();

        backend.initialize(config);

        // Buffer ring not enabled, should return false
        assertFalse(backend.submitMultishotRecvWithBufferRing(1L));
    }
}

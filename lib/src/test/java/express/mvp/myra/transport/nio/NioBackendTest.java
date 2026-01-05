package express.mvp.myra.transport.nio;

import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import express.mvp.myra.transport.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for NioBackend.
 *
 * <p>Tests cover:
 *
 * <ul>
 *   <li>Connection lifecycle and state management
 *   <li>Send/receive with zero-copy MemorySegment
 *   <li>Statistics tracking
 *   <li>Error handling and state transitions
 * </ul>
 */
@SuppressFBWarnings(
        value = {"THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION"},
        justification = "SpotBugs rules are intentionally relaxed for test scaffolding.")
class NioBackendTest {

    private NioBackend clientBackend;
    private NioBackend serverBackend;
    private RegisteredBufferPool bufferPool;
    private TransportConfig config;

    private static final int TEST_PORT = 9876;
    private static final String TEST_HOST = "localhost";

    @BeforeEach
    void setUp() {
        clientBackend = new NioBackend();
        serverBackend = new NioBackend();
        bufferPool = new RegisteredBufferPoolImpl(16, 4096);

        config = TransportConfig.builder().backendType(TransportConfig.BackendType.NIO).build();

        clientBackend.initialize(config);
        serverBackend.initialize(config);

        clientBackend.registerBufferPool(bufferPool);
        serverBackend.registerBufferPool(bufferPool);
    }

    @AfterEach
    void tearDown() {
        if (clientBackend != null) {
            clientBackend.close();
        }
        if (serverBackend != null) {
            serverBackend.close();
        }
        if (bufferPool != null) {
            bufferPool.close();
        }
    }

    @Test
    void testBackendType() {
        assertEquals("nio", clientBackend.getBackendType());
    }

    @Test
    void testSupportedFeatures() {
        assertFalse(clientBackend.supportsRegisteredBuffers());
        assertFalse(clientBackend.supportsBatchSubmission());
        assertTrue(clientBackend.supportsTLS());
    }

    @Test
    void testConnectionState() {
        // Initial state
        assertEquals(NioBackend.ConnectionState.DISCONNECTED, clientBackend.getConnectionState());

        // After bind, server is "connected"
        InetSocketAddress serverAddr = new InetSocketAddress(TEST_HOST, TEST_PORT);
        serverBackend.bind(serverAddr);
        assertEquals(NioBackend.ConnectionState.CONNECTED, serverBackend.getConnectionState());

        // After close
        serverBackend.close();
        assertEquals(NioBackend.ConnectionState.CLOSED, serverBackend.getConnectionState());
    }

    @Test
    void testDoubleInitializationThrows() {
        assertThrows(
                IllegalStateException.class,
                () -> {
                    clientBackend.initialize(config);
                });
    }

    @Test
    void testOperationBeforeInitializationThrows() {
        try (NioBackend uninitBackend = new NioBackend()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        uninitBackend.registerBufferPool(bufferPool);
                    });
        }
    }

    @Test
    void testStatisticsTracking() throws Exception {
        TestCompletionHandler handler = new TestCompletionHandler();

        // Start server and connect
        InetSocketAddress serverAddr = new InetSocketAddress(TEST_HOST, TEST_PORT + 1);
        serverBackend.bind(serverAddr);
        serverBackend.accept(1);
        clientBackend.connect(serverAddr, 2);

        // Poll until connected
        pollUntilComplete(handler, 2, 1);

        // Get stats - should have syscalls tracked
        BackendStats clientStats = clientBackend.getStats();
        assertTrue(clientStats.getTotalSyscalls() > 0, "Should track syscalls");

        // batchSubmissions should be 0 for NIO
        assertEquals(0, clientStats.getBatchSubmissions());
    }

    @Test
    void testConnectAndSend() throws Exception {
        TestCompletionHandler handler = new TestCompletionHandler();

        // Start server
        InetSocketAddress serverAddr = new InetSocketAddress(TEST_HOST, TEST_PORT + 2);
        serverBackend.bind(serverAddr);

        // Accept connection
        long acceptToken = 1;
        serverBackend.accept(acceptToken);

        // Connect client
        long connectToken = 2;
        clientBackend.connect(serverAddr, connectToken);

        // Poll until connected
        pollUntilComplete(handler, connectToken, acceptToken);

        // Get accepted connection
        int acceptHandle = handler.getResult(acceptToken);
        TransportBackend acceptedBackend = serverBackend.createFromAccepted(acceptHandle);
        assertNotNull(acceptedBackend);

        // Verify accepted backend is in CONNECTED state
        assertTrue(acceptedBackend instanceof NioBackend);
        assertEquals(
                NioBackend.ConnectionState.CONNECTED,
                ((NioBackend) acceptedBackend).getConnectionState());

        // Send data from client
        String testMessage = "Hello NIO!";
        byte[] messageBytes = testMessage.getBytes(StandardCharsets.UTF_8);

        try (RegisteredBuffer sendBuffer = bufferPool.acquire()) {
            MemorySegment segment = sendBuffer.segment();
            for (int i = 0; i < messageBytes.length; i++) {
                segment.set(ValueLayout.JAVA_BYTE, i, messageBytes[i]);
            }

            long sendToken = 3;
            clientBackend.send(segment, messageBytes.length, sendToken);

            // Receive on server
            try (RegisteredBuffer recvBuffer = bufferPool.acquire()) {
                long recvToken = 4;
                acceptedBackend.receive(
                        recvBuffer.segment(), (int) recvBuffer.capacity(), recvToken);

                // Poll until transfer complete
                long start = System.currentTimeMillis();
                while (!handler.isComplete(sendToken) || !handler.isComplete(recvToken)) {
                    if (System.currentTimeMillis() - start > 5000)
                        fail("Timeout waiting for transfer");
                    clientBackend.poll(handler);
                    acceptedBackend.poll(handler);
                    Thread.sleep(1);
                }

                assertEquals(messageBytes.length, handler.getResult(sendToken));
                assertEquals(messageBytes.length, handler.getResult(recvToken));

                // Verify data
                byte[] receivedBytes = new byte[messageBytes.length];
                MemorySegment.copy(
                        recvBuffer.segment(),
                        0,
                        MemorySegment.ofArray(receivedBytes),
                        0,
                        messageBytes.length);
                assertArrayEquals(messageBytes, receivedBytes);

                // Verify stats
                BackendStats clientStats = clientBackend.getStats();
                assertEquals(1, clientStats.getTotalSends());
                assertEquals(messageBytes.length, clientStats.getTotalBytesSent());
            }
        }

        acceptedBackend.close();
    }

    @Test
    void testMultipleAccepts() throws Exception {
        TestCompletionHandler handler = new TestCompletionHandler();

        // Start server
        InetSocketAddress serverAddr = new InetSocketAddress(TEST_HOST, TEST_PORT + 3);
        serverBackend.bind(serverAddr);

        // Accept first connection
        serverBackend.accept(1);
        NioBackend client1 = new NioBackend();
        client1.initialize(config);
        client1.connect(serverAddr, 101);

        // Poll both until connected
        long start = System.currentTimeMillis();
        while (!handler.isComplete(101) || !handler.isComplete(1)) {
            if (System.currentTimeMillis() - start > 5000)
                fail("Timeout waiting for first connection");
            client1.poll(handler);
            serverBackend.poll(handler);
            Thread.sleep(1);
        }
        int handle1 = handler.getResult(1);

        // Accept second connection
        serverBackend.accept(2);
        NioBackend client2 = new NioBackend();
        client2.initialize(config);
        client2.connect(serverAddr, 102);

        // Poll both until connected
        start = System.currentTimeMillis();
        while (!handler.isComplete(102) || !handler.isComplete(2)) {
            if (System.currentTimeMillis() - start > 5000)
                fail("Timeout waiting for second connection");
            client2.poll(handler);
            serverBackend.poll(handler);
            Thread.sleep(1);
        }
        int handle2 = handler.getResult(2);

        // Handles should be different
        assertNotEquals(handle1, handle2);

        // Create backends from handles
        TransportBackend accepted1 = serverBackend.createFromAccepted(handle1);
        TransportBackend accepted2 = serverBackend.createFromAccepted(handle2);

        assertNotNull(accepted1);
        assertNotNull(accepted2);

        // Cleanup
        accepted1.close();
        accepted2.close();
        client1.close();
        client2.close();
    }

    private void pollUntilComplete(TestCompletionHandler handler, long... tokens) throws Exception {
        long start = System.currentTimeMillis();
        while (true) {
            boolean allComplete = true;
            for (long token : tokens) {
                if (!handler.isComplete(token)) {
                    allComplete = false;
                    break;
                }
            }
            if (allComplete) return;

            if (System.currentTimeMillis() - start > 5000) {
                fail("Timeout waiting for tokens");
            }

            clientBackend.poll(handler);
            serverBackend.poll(handler);
            Thread.sleep(1);
        }
    }

    static class TestCompletionHandler implements CompletionHandler {
        final Map<Long, Integer> results = new ConcurrentHashMap<>();

        @Override
        public void onComplete(long token, int result) {
            results.put(token, result);
        }

        int getResult(long token) {
            return results.getOrDefault(token, Integer.MIN_VALUE);
        }

        boolean isComplete(long token) {
            return results.containsKey(token);
        }
    }
}

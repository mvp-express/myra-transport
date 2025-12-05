package express.mvp.myra.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PingPongTest {

    private Transport server;
    private Transport client;
    private TransportBackend serverBackend;
    private TransportBackend clientBackend;
    private RegisteredBufferPool serverPool;
    private RegisteredBufferPool clientPool;

    private static final int PORT = 9876;
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", PORT);

    @BeforeEach
    void setUp() {
        // Setup is done in tests to allow parameterization
    }

    private java.nio.channels.ServerSocketChannel echoServerSocket;

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.close();
        if (serverBackend != null)
            try {
                serverBackend.close();
            } catch (Exception e) {
            }
        if (echoServerSocket != null)
            try {
                echoServerSocket.close();
            } catch (Exception e) {
            }
    }

    private void setupTransports(TransportConfig.BackendType backendType) {
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(backendType)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(16)
                                        .bufferSize(1024)
                                        .build())
                        .connectionTimeout(Duration.ofSeconds(5))
                        .build();

        // Client Setup
        TransportBackend cBackend = TransportFactory.createBackend(config);
        try {
            cBackend.initialize(config);
        } catch (TransportException e) {
            if (e.getMessage().contains("not available")) {
                Assumptions.assumeTrue(false, "Backend not available: " + e.getMessage());
            }
            throw e;
        }
        clientPool = new RegisteredBufferPoolImpl(16, 1024);
        cBackend.registerBufferPool(clientPool);
        client = new TcpTransport(cBackend, clientPool, ADDRESS, -1);
    }

    @ParameterizedTest
    @EnumSource(TransportConfig.BackendType.class)
    @Timeout(10)
    void testPingPong(TransportConfig.BackendType backendType) throws InterruptedException {
        if (backendType == TransportConfig.BackendType.XDP
                || backendType == TransportConfig.BackendType.DPDK) {
            return; // Skip unsupported backends
        }

        setupTransports(backendType);

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch receivedLatch = new CountDownLatch(1);
        AtomicInteger receivedValue = new AtomicInteger(0);

        // Start Echo Server (Standard Java NIO)
        Thread echoServer =
                new Thread(
                        () -> {
                            try (java.nio.channels.ServerSocketChannel serverSocket =
                                    java.nio.channels.ServerSocketChannel.open()) {
                                echoServerSocket = serverSocket;
                                serverSocket.bind(ADDRESS);
                                try (java.nio.channels.SocketChannel socket =
                                        serverSocket.accept()) {
                                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024);
                                    while (socket.read(buffer) != -1) {
                                        buffer.flip();
                                        while (buffer.hasRemaining()) {
                                            socket.write(buffer);
                                        }
                                        buffer.clear();
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore closed exception during shutdown
                                if (!e.getMessage().contains("closed")) {
                                    e.printStackTrace();
                                }
                            }
                        });
        echoServer.start();

        // Give server a moment
        Thread.sleep(100);

        client.start(
                new TransportHandlerAdapter() {
                    @Override
                    public void onConnected(long token) {
                        System.out.println("Client connected: " + token);
                        connectedLatch.countDown();
                    }

                    @Override
                    public void onConnectionFailed(long token, Throwable cause) {
                        System.err.println("Client connection failed: " + cause.getMessage());
                        cause.printStackTrace();
                    }

                    @Override
                    public void onSendComplete(long token) {
                        System.out.println("Client send complete: " + token);
                    }

                    @Override
                    public void onSendFailed(long token, Throwable cause) {
                        System.err.println("Client send failed: " + cause.getMessage());
                        cause.printStackTrace();
                    }

                    @Override
                    public void onDataReceived(MemorySegment data) {
                        int val = data.get(ValueLayout.JAVA_INT, 0);
                        System.out.println("Client received data: " + val);
                        // Only capture the first received value to avoid race with subsequent empty
                        // reads
                        if (receivedLatch.getCount() > 0) {
                            receivedValue.set(val);
                            receivedLatch.countDown();
                        }
                    }
                });

        client.connect(ADDRESS);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client failed to connect");

        RegisteredBuffer buf = client.acquireBuffer();
        buf.segment().set(ValueLayout.JAVA_INT, 0, 42);
        client.send(buf.segment());
        buf.close();

        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "Client didn't receive echo");
        assertEquals(42, receivedValue.get());

        echoServer.join(1000);
    }
}

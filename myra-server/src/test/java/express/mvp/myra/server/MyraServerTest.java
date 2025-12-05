package express.mvp.myra.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import express.mvp.myra.transport.RegisteredBuffer;
import express.mvp.myra.transport.RegisteredBufferPool;
import express.mvp.myra.transport.RegisteredBufferPoolImpl;
import express.mvp.myra.transport.TcpTransport;
import express.mvp.myra.transport.TransportBackend;
import express.mvp.myra.transport.TransportConfig;
import express.mvp.myra.transport.TransportFactory;
import express.mvp.myra.transport.TransportHandlerAdapter;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MyraServerTest {

    private MyraServer server;
    private MyraServerConfig config;
    private TestHandler handler;

    @BeforeEach
    void setUp() throws InterruptedException {
        config = MyraServerConfig.builder().port(9999).build();
        handler = new TestHandler();
        server = new MyraServer(config, handler);
        server.start();
        assertTrue(server.awaitReady(5, TimeUnit.SECONDS), "Server failed to start");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testConnectionAndEcho() throws Exception {
        // Setup Client
        TransportConfig clientConfig =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(4)
                                        .bufferSize(1024)
                                        .build())
                        .build();

        TransportBackend clientBackend = TransportFactory.createBackend(clientConfig);
        clientBackend.initialize(clientConfig);
        RegisteredBufferPool clientPool = new RegisteredBufferPoolImpl(4, 1024);
        clientBackend.registerBufferPool(clientPool);

        CountDownLatch connectLatch = new CountDownLatch(1);
        CountDownLatch receiveLatch = new CountDownLatch(1);
        AtomicReference<MemorySegment> receivedData = new AtomicReference<>();

        TcpTransport client =
                new TcpTransport(
                        clientBackend, clientPool, new InetSocketAddress("127.0.0.1", 9999), -1);
        client.start(
                new TransportHandlerAdapter() {
                    @Override
                    public void onConnected(long token) {
                        connectLatch.countDown();
                    }

                    @Override
                    public void onDataReceived(MemorySegment data) {
                        receivedData.set(data);
                        receiveLatch.countDown();
                    }
                });

        client.connect(new InetSocketAddress("127.0.0.1", 9999));
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS), "Client failed to connect");

        // Send Data
        RegisteredBuffer sendBuf = client.acquireBuffer();
        sendBuf.segment().set(ValueLayout.JAVA_INT, 0, 42);
        client.send(sendBuf.segment());

        assertTrue(receiveLatch.await(5, TimeUnit.SECONDS), "Client failed to receive echo");
        assertEquals(42, receivedData.get().get(ValueLayout.JAVA_INT, 0));

        // Allow time for any in-flight operations to complete before cleanup
        Thread.sleep(100);

        client.close();
        clientBackend.close();
    }

    static class TestHandler implements MyraServerHandler {
        @Override
        public void onConnect(TransportBackend connection) {
            System.out.println("Server: Client connected");
        }

        @Override
        public void onDataReceived(
                TransportBackend connection, RegisteredBuffer buffer, int length) {
            System.out.println("Server: Received " + length + " bytes");
            // Echo back
            connection.send(buffer, 3001);
        }

        @Override
        public void onDisconnect(TransportBackend connection) {
            System.out.println("Server: Client disconnected");
        }
    }
}

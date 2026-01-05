package express.mvp.myra.transport;

import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import express.mvp.myra.transport.iouring.LibUring;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Smoke tests for io_uring buffer modes using a ping-pong exchange. */
@SuppressFBWarnings(
        value = {
            "REC_CATCH_EXCEPTION",
            "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            "UCF_USELESS_CONTROL_FLOW"
        },
        justification = "SpotBugs rules are intentionally relaxed for test scaffolding.")
class IoUringBufferModePingPongTest {

    private static final int PORT = 9877;
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", PORT);

    @Test
    @Timeout(10)
    void pingPong_fixedBuffers() throws Exception {
        runPingPong(TransportConfig.BufferMode.FIXED);
    }

    @Test
    @Timeout(10)
    void pingPong_zeroCopySend() throws Exception {
        runPingPong(TransportConfig.BufferMode.ZERO_COPY);
    }

    @Test
    @Timeout(10)
    void pingPong_bufferRing() throws Exception {
        Assumptions.assumeTrue(
                LibUring.isBufferRingSupported(), "Buffer ring not supported by liburing");
        runPingPong(TransportConfig.BufferMode.BUFFER_RING);
    }

    private void runPingPong(TransportConfig.BufferMode mode) throws Exception {
        TransportConfig config =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .bufferMode(mode)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(64)
                                        .bufferSize(1024)
                                        .build())
                        .connectionTimeout(Duration.ofSeconds(5))
                        .build();

        Transport transport;
        try {
            transport = TransportFactory.create(config, ADDRESS);
        } catch (TransportException e) {
            Assumptions.assumeTrue(false, "io_uring backend not available: " + e.getMessage());
            return;
        }

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch sendLatch = new CountDownLatch(1);
        CountDownLatch receivedLatch = new CountDownLatch(1);
        AtomicInteger receivedValue = new AtomicInteger(0);

        Thread echoServer =
                new Thread(
                        () -> {
                            try (java.nio.channels.ServerSocketChannel serverSocket =
                                    java.nio.channels.ServerSocketChannel.open()) {
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
                                if (e.getMessage() == null || !e.getMessage().contains("closed")) {
                                    // best effort
                                }
                            }
                        },
                        "echo-server");
        echoServer.start();

        Thread.sleep(100);

        try {
            transport.start(
                    new TransportHandlerAdapter() {
                        @Override
                        public void onConnected(long token) {
                            connectedLatch.countDown();
                        }

                        @Override
                        public void onConnectionFailed(long token, Throwable cause) {
                            // Ensure tests fail loudly on connection failures
                            fail("Connection failed: " + cause);
                        }

                        @Override
                        public void onSendComplete(long token) {
                            sendLatch.countDown();
                        }

                        @Override
                        public void onSendFailed(long token, Throwable cause) {
                            fail("Send failed (mode=" + mode + "): " + cause);
                        }

                        @Override
                        public void onDataReceived(MemorySegment data) {
                            if (receivedLatch.getCount() == 0) return;
                            int val = data.get(ValueLayout.JAVA_INT, 0);
                            receivedValue.set(val);
                            receivedLatch.countDown();
                        }
                    });

            transport.connect(ADDRESS);
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client failed to connect");

            // Send an int payload
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                MemorySegment payload = arena.allocate(ValueLayout.JAVA_INT);
                payload.set(ValueLayout.JAVA_INT, 0, 42);
                transport.send(payload);
            }

            assertTrue(sendLatch.await(5, TimeUnit.SECONDS), "Send did not complete");

            assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "Client didn't receive echo");
            assertEquals(42, receivedValue.get());
        } finally {
            transport.close();
            echoServer.join(1000);
        }
    }
}

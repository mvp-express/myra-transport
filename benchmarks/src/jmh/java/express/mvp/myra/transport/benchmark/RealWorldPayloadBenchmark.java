package express.mvp.myra.transport.benchmark;

import express.mvp.myra.transport.*;
import express.mvp.myra.server.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JMH benchmark that exercises a larger payload to simulate real-world message sizes.
 *
 * <p>Compares transport implementations under a fixed payload size and configurable buffer modes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 1, time = 30)
public class RealWorldPayloadBenchmark {

    @Param({ "NIO", "NETTY", "MYRA", "MYRA_SQPOLL", "MYRA_TOKEN", "MYRA_NIO" })
    private String implementation;

    @Param({ "STANDARD", "FIXED", "BUFFER_RING", "ZERO_COPY" })
    private String bufferMode;

    @Param("true")
    private boolean pinning;

    @Param("0")
    private int serverCore;

    @Param("1")
    private int clientCore;

    @Param("2")
    private int serverSqPollCore;

    @Param("3")
    private int clientSqPollCore;

    @Param("3")
    private int clientPollerCore;

    private BenchmarkDriver driver;

    private static final int PAYLOAD_SIZE = 2048;

    @Setup
    public void setup() throws Exception {
        if (pinning) {
            express.mvp.myra.transport.util.NativeThread.pin(clientCore);
        }

        // cleanup previous runs
        System.gc();
        Thread.sleep(1000);

        int port = java.util.concurrent.ThreadLocalRandom.current().nextInt(10000, 60000);
        System.out.println("Using port: " + port);

        switch (implementation) {
            case "NIO" -> driver = new NioDriver(port, pinning ? serverCore : -1);
            case "NETTY" -> driver = new NettyDriver(port, pinning ? serverCore : -1);
            case "MYRA" -> driver = new MyraDriver(port, false, false, bufferMode, pinning ? serverCore : -1,
                    pinning ? clientCore : -1, -1, -1, pinning ? clientCore : -1);
            case "MYRA_SQPOLL" ->
                driver = new MyraDriver(port, true, false, bufferMode, pinning ? serverCore : -1, pinning ? clientCore : -1,
                        pinning ? serverSqPollCore : -1, pinning ? clientSqPollCore : -1, pinning ? clientCore : -1);
            case "MYRA_TOKEN" -> driver = new MyraDriver(port, false, true, bufferMode, pinning ? serverCore : -1,
                    pinning ? clientCore : -1,
                    pinning ? serverSqPollCore : -1, pinning ? clientSqPollCore : -1, pinning ? clientPollerCore : -1);
            case "MYRA_NIO" -> driver = new MyraNioDriver(port, pinning ? serverCore : -1, pinning ? clientCore : -1);
            default -> throw new IllegalArgumentException("Unknown implementation: " + implementation);
        }
        driver.setup();
    }

    @TearDown
    public void tearDown() {
        if (driver != null) {
            driver.tearDown();
        }
    }

    @Benchmark
    public void pingPong() throws Exception {
        driver.pingPong();
    }

    interface BenchmarkDriver {
        void setup() throws Exception;

        void pingPong() throws Exception;

        void tearDown();
    }

    static class PayloadHelper {
        static final byte[] PADDING = new byte[1956];
        static final byte[] SYMBOL = "AAPL            ".getBytes(StandardCharsets.US_ASCII); // 16
        static final byte[] ACCOUNT = "ACC-9999999999999999999999999999".getBytes(StandardCharsets.US_ASCII); // 32

        static {
            for (int i = 0; i < PADDING.length; i++)
                PADDING[i] = (byte) 'x';
        }

        static void write(ByteBuffer buf) {
            buf.clear();
            buf.putLong(System.nanoTime()); // timestamp
            buf.putLong(123456789L); // orderId
            buf.putInt(1001); // userId
            buf.putInt(55); // symbolId
            buf.putDouble(150.25); // price
            buf.putDouble(100.0); // quantity
            buf.put((byte) 1); // isBuy
            buf.put((byte) 1); // isMarket
            buf.putShort((short) 0); // flags
            buf.put(SYMBOL);
            buf.put(ACCOUNT);
            buf.put(PADDING);
            buf.flip();
        }

        static void write(ByteBuf buf) {
            buf.clear();
            buf.writeLong(System.nanoTime());
            buf.writeLong(123456789L);
            buf.writeInt(1001);
            buf.writeInt(55);
            buf.writeDouble(150.25);
            buf.writeDouble(100.0);
            buf.writeByte(1);
            buf.writeByte(1);
            buf.writeShort(0);
            buf.writeBytes(SYMBOL);
            buf.writeBytes(ACCOUNT);
            buf.writeBytes(PADDING);
        }

        static void write(MemorySegment seg) {
            long offset = 0;
            seg.set(ValueLayout.JAVA_LONG, offset, System.nanoTime());
            offset += 8;
            seg.set(ValueLayout.JAVA_LONG, offset, 123456789L);
            offset += 8;
            seg.set(ValueLayout.JAVA_INT, offset, 1001);
            offset += 4;
            seg.set(ValueLayout.JAVA_INT, offset, 55);
            offset += 4;
            seg.set(ValueLayout.JAVA_DOUBLE, offset, 150.25);
            offset += 8;
            seg.set(ValueLayout.JAVA_DOUBLE, offset, 100.0);
            offset += 8;
            seg.set(ValueLayout.JAVA_BYTE, offset, (byte) 1);
            offset += 1;
            seg.set(ValueLayout.JAVA_BYTE, offset, (byte) 1);
            offset += 1;
            seg.set(ValueLayout.JAVA_SHORT, offset, (short) 0);
            offset += 2;

            MemorySegment.copy(MemorySegment.ofArray(SYMBOL), 0, seg, offset, SYMBOL.length);
            offset += SYMBOL.length;
            MemorySegment.copy(MemorySegment.ofArray(ACCOUNT), 0, seg, offset, ACCOUNT.length);
            offset += ACCOUNT.length;
            MemorySegment.copy(MemorySegment.ofArray(PADDING), 0, seg, offset, PADDING.length);
        }
    }

    // ==========================================
    // NIO Implementation (Non-blocking + Async with Selector spin-wait)
    // ==========================================
    static class NioDriver implements BenchmarkDriver {
        private java.nio.channels.ServerSocketChannel serverSocket;
        private java.nio.channels.SocketChannel clientChannel;
        private java.nio.channels.Selector clientSelector;
        private Thread serverThread;
        private volatile boolean running = true;
        private final ByteBuffer clientBuffer = ByteBuffer.allocateDirect(PAYLOAD_SIZE);
        private final int serverCore;
        private final int port;

        public NioDriver(int port, int serverCore) {
            this.port = port;
            this.serverCore = serverCore;
        }

        @Override
        public void setup() throws Exception {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            CountDownLatch serverStarted = new CountDownLatch(1);
            AtomicReference<Exception> startupError = new AtomicReference<>();

            serverThread = new Thread(() -> {
                if (serverCore >= 0) {
                    express.mvp.myra.transport.util.NativeThread.pin(serverCore);
                }
                try {
                    serverSocket = java.nio.channels.ServerSocketChannel.open();
                    serverSocket.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                    serverSocket.bind(address);
                    serverStarted.countDown();

                    try (java.nio.channels.SocketChannel socket = serverSocket.accept()) {
                        socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                        socket.configureBlocking(false); // Non-blocking server
                        
                        java.nio.channels.Selector serverSelector = java.nio.channels.Selector.open();
                        socket.register(serverSelector, java.nio.channels.SelectionKey.OP_READ);
                        ByteBuffer buffer = ByteBuffer.allocateDirect(PAYLOAD_SIZE);
                        
                        while (running) {
                            // Spin-wait polling (no blocking select)
                            if (serverSelector.selectNow() > 0) {
                                var keys = serverSelector.selectedKeys().iterator();
                                while (keys.hasNext()) {
                                    var key = keys.next();
                                    keys.remove();
                                    
                                    if (key.isReadable()) {
                                        buffer.clear();
                                        // Read full payload
                                        while (buffer.hasRemaining()) {
                                            int read = socket.read(buffer);
                                            if (read == -1) return;
                                            if (read == 0) Thread.onSpinWait();
                                        }
                                        buffer.flip();
                                        // Write full payload
                                        while (buffer.hasRemaining()) {
                                            int written = socket.write(buffer);
                                            if (written == 0) Thread.onSpinWait();
                                        }
                                    }
                                }
                            }
                            Thread.onSpinWait();
                        }
                    } catch (Exception e) {
                        if (running)
                            e.printStackTrace();
                    }
                } catch (Exception e) {
                    startupError.set(e);
                    serverStarted.countDown();
                    if (running)
                        e.printStackTrace();
                }
            });
            serverThread.start();

            if (!serverStarted.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Server failed to start (timeout)");
            }
            if (startupError.get() != null) {
                throw new RuntimeException("Server failed to start", startupError.get());
            }
            Thread.sleep(20); // Let server set up selector

            // Client: non-blocking with selector
            clientChannel = java.nio.channels.SocketChannel.open();
            clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            clientChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            clientChannel.configureBlocking(false); // Non-blocking client
            clientChannel.connect(address);
            
            // Spin-wait for connection
            while (!clientChannel.finishConnect()) {
                Thread.onSpinWait();
            }
            
            clientSelector = java.nio.channels.Selector.open();
        }

        @Override
        public void pingPong() throws Exception {
            PayloadHelper.write(clientBuffer);

            // Non-blocking write with spin-wait
            while (clientBuffer.hasRemaining()) {
                int written = clientChannel.write(clientBuffer);
                if (written == 0) Thread.onSpinWait();
            }

            // Non-blocking read with spin-wait
            clientBuffer.clear();
            while (clientBuffer.hasRemaining()) {
                int read = clientChannel.read(clientBuffer);
                if (read == -1) {
                    throw new RuntimeException("Connection closed");
                }
                if (read == 0) Thread.onSpinWait();
            }
        }

        @Override
        public void tearDown() {
            running = false;
            try {
                if (clientSelector != null)
                    clientSelector.close();
            } catch (Exception e) {
            }
            try {
                if (clientChannel != null)
                    clientChannel.close();
            } catch (Exception e) {
            }
            try {
                if (serverSocket != null)
                    serverSocket.close();
            } catch (Exception e) {
            }
        }
    }

    // ==========================================
    // Netty Implementation
    // ==========================================
    static class NettyDriver implements BenchmarkDriver {
        private EventLoopGroup bossGroup;
        private EventLoopGroup workerGroup;
        private EventLoopGroup clientGroup;
        private Channel serverChannel;
        private Channel clientChannel;
        private CountDownLatch latch;
        private final ByteBuf msg = Unpooled.directBuffer(PAYLOAD_SIZE);
        private final int serverCore;
        private final int port;

        public NettyDriver(int port, int serverCore) {
            this.port = port;
            this.serverCore = serverCore;
        }

        @Override
        public void setup() throws Exception {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(1);
            clientGroup = new NioEventLoopGroup(1);

            ServerBootstrap sb = new ServerBootstrap();
            sb.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    ctx.writeAndFlush(msg);
                                }
                            });
                        }
                    });

            serverChannel = sb.bind(port).sync().channel();

            Bootstrap cb = new Bootstrap();
            cb.group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof ByteBuf) {
                                        ((ByteBuf) msg).release();
                                    }
                                    if (latch != null)
                                        latch.countDown();
                                }
                            });
                        }
                    });

            clientChannel = cb.connect("127.0.0.1", port).sync().channel();
        }

        @Override
        public void pingPong() throws Exception {
            latch = new CountDownLatch(1);
            PayloadHelper.write(msg);
            msg.retain();
            clientChannel.writeAndFlush(msg);
            latch.await();
        }

        @Override
        public void tearDown() {
            try {
                if (clientGroup != null)
                    clientGroup.shutdownGracefully().sync();
                if (workerGroup != null)
                    workerGroup.shutdownGracefully().sync();
                if (bossGroup != null)
                    bossGroup.shutdownGracefully().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==========================================
    // Myra NIO Implementation (FFM-based NIO backend)
    // ==========================================
    static class MyraNioDriver implements BenchmarkDriver {
        private Thread serverThread;
        private volatile boolean serverRunning = true;
        private TransportBackend clientBackend;
        private RegisteredBufferPool clientPool;
        private RegisteredBuffer clientBuffer;
        private volatile CountDownLatch latch;
        private final int serverCore;
        private final int clientCore;
        private final int port;
        private TransportBackend serverBackend;
        private RegisteredBufferPool serverPool;

        public MyraNioDriver(int port, int serverCore, int clientCore) {
            this.port = port;
            this.serverCore = serverCore;
            this.clientCore = clientCore;
        }

        @Override
        public void setup() throws Exception {
            CountDownLatch serverStarted = new CountDownLatch(1);
            CountDownLatch clientAccepted = new CountDownLatch(1);
            
            // Server thread using NIO backend
            serverThread = new Thread(() -> {
                if (serverCore >= 0) {
                    express.mvp.myra.transport.util.NativeThread.pin(serverCore);
                }
                try {
                    TransportConfig serverConfig = TransportConfig.builder()
                            .backendType(TransportConfig.BackendType.NIO)
                            .registeredBuffers(TransportConfig.RegisteredBuffersConfig.builder()
                                    .enabled(true)
                                    .numBuffers(16)
                                    .bufferSize(PAYLOAD_SIZE)
                                    .build())
                            .build();
                    
                    serverBackend = TransportFactory.createBackend(serverConfig);
                    serverBackend.initialize(serverConfig);
                    serverPool = new RegisteredBufferPoolImpl(16, PAYLOAD_SIZE);
                    serverBackend.registerBufferPool(serverPool);
                    
                    serverBackend.bind(new InetSocketAddress("127.0.0.1", port));
                    serverBackend.accept(1);
                    serverStarted.countDown();
                    
                    // Wait for accept completion on server backend
                    TransportBackend[] clientConn = {null};
                    RegisteredBuffer[] recvBuffer = {null};
                    
                    // Phase 1: Wait for accept
                    while (serverRunning && clientConn[0] == null) {
                        serverBackend.poll((token, result) -> {
                            if (token == 1 && result >= 0) {
                                // Accept completed - create client connection backend
                                clientConn[0] = serverBackend.createFromAccepted(result);
                                recvBuffer[0] = serverPool.acquire();
                                clientConn[0].receive(recvBuffer[0], 2);
                                clientAccepted.countDown();
                            }
                        });
                        Thread.onSpinWait();
                    }
                    
                    // Phase 2: Echo loop - poll on CLIENT connection backend!
                    while (serverRunning && clientConn[0] != null) {
                        clientConn[0].poll((token, result) -> {
                            if (token == 2 && result > 0) {
                                // Receive completed - echo back
                                clientConn[0].send(recvBuffer[0].segment(), result, 3);
                            } else if (token == 3 && result >= 0) {
                                // Send completed - receive next
                                recvBuffer[0].clear();
                                clientConn[0].receive(recvBuffer[0], 2);
                            } else if (result < 0) {
                                // Error or EOF
                                serverRunning = false;
                            }
                        });
                        Thread.onSpinWait();
                    }
                    
                    if (clientConn[0] != null) clientConn[0].close();
                    if (recvBuffer[0] != null) recvBuffer[0].close();
                } catch (Exception e) {
                    if (serverRunning) e.printStackTrace();
                }
            });
            serverThread.start();
            serverStarted.await();
            Thread.sleep(20); // Let server start accepting

            // Client Setup using NIO backend (direct, no TcpTransport)
            TransportConfig clientConfig = TransportConfig.builder()
                    .backendType(TransportConfig.BackendType.NIO)
                    .registeredBuffers(TransportConfig.RegisteredBuffersConfig.builder()
                            .enabled(true)
                            .numBuffers(16)
                            .bufferSize(PAYLOAD_SIZE)
                            .build())
                    .build();

            clientBackend = TransportFactory.createBackend(clientConfig);
            clientBackend.initialize(clientConfig);
            clientPool = new RegisteredBufferPoolImpl(16, PAYLOAD_SIZE);
            clientBackend.registerBufferPool(clientPool);

            // Connect directly using the backend
            clientBackend.connect(new InetSocketAddress("127.0.0.1", port), 100);
            
            // Wait for connect completion
            CountDownLatch connectLatch = new CountDownLatch(1);
            while (connectLatch.getCount() > 0) {
                clientBackend.poll((token, result) -> {
                    if (token == 100) {
                        if (result >= 0) {
                            connectLatch.countDown();
                        }
                    }
                });
                Thread.onSpinWait();
            }
            
            // Wait for server to accept
            clientAccepted.await(5, TimeUnit.SECONDS);
            
            clientBuffer = clientPool.acquire();
        }

        @Override
        public void pingPong() throws Exception {
            latch = new CountDownLatch(1);
            
            // Write payload
            PayloadHelper.write(clientBuffer.segment());
            
            // Send ping
            clientBackend.send(clientBuffer.segment(), PAYLOAD_SIZE, 200);
            
            // Wait for send completion and then receive
            boolean sendComplete = false;
            while (!sendComplete || latch.getCount() > 0) {
                clientBackend.poll((token, result) -> {
                    if (token == 200 && result >= 0) {
                        // Send completed, start receive
                        clientBuffer.clear();
                        clientBackend.receive(clientBuffer, 201);
                    } else if (token == 201 && result > 0) {
                        // Receive completed
                        latch.countDown();
                    }
                });
                if (!sendComplete) {
                    sendComplete = true; // First poll handles send
                }
                Thread.onSpinWait();
            }
        }

        @Override
        public void tearDown() {
            serverRunning = false;
            if (clientBackend != null) clientBackend.close();
            if (clientPool != null) clientPool.close();
            if (serverBackend != null) serverBackend.close();
            if (serverPool != null) serverPool.close();
        }
    }

    // ==========================================
    // Myra Implementation
    // ==========================================
    static class MyraDriver implements BenchmarkDriver {
        private MyraServer server;
        private TcpTransport client;
        private RegisteredBuffer clientBuffer;
        private volatile CountDownLatch latch;
        private volatile boolean tokenReceived;
        private final boolean sqPoll;
        private final boolean tokenMode;
        private final TransportConfig.BufferMode bufferMode;
        private final int serverCore;
        private final int clientCore;
        private final int serverSqPollCore;
        private final int clientSqPollCore;
        private final int clientPollerCore;
        private final int port;

        public MyraDriver(int port, boolean sqPoll, boolean tokenMode, String bufferMode, int serverCore, int clientCore,
                int serverSqPollCore, int clientSqPollCore, int clientPollerCore) {
            this.port = port;
            this.sqPoll = sqPoll;
            this.tokenMode = tokenMode;
            this.bufferMode = TransportConfig.BufferMode.valueOf(bufferMode);
            this.serverCore = serverCore;
            this.clientCore = clientCore;
            this.serverSqPollCore = serverSqPollCore;
            this.clientSqPollCore = clientSqPollCore;
            this.clientPollerCore = clientPollerCore;
        }

        @Override
        public void setup() throws Exception {
            // Server Setup
            MyraServerConfig serverConfig = MyraServerConfig.builder()
                    .host("127.0.0.1")
                    .port(port)
                    .numBuffers(16)
                    .bufferSize(PAYLOAD_SIZE)
                    .sqPollEnabled(sqPoll)
                    .cpuAffinity(serverCore)
                    .sqPollCpuAffinity(serverSqPollCore)
                    .build();

            server = new MyraServer(serverConfig, new MyraServerHandler() {
                @Override
                public void onConnect(TransportBackend connection) {
                }

                @Override
                public void onDataReceived(TransportBackend connection, RegisteredBuffer buffer, int length) {
                    // Echo back
                    connection.send(buffer, 3001);
                }

                @Override
                public void onDisconnect(TransportBackend connection) {
                }
            });
            server.start();
            Thread.sleep(100);

            // Client Setup
            TransportConfig clientConfig = TransportConfig.builder()
                    .backendType(TransportConfig.BackendType.IO_URING)
                    .bufferMode(bufferMode)
                    .registeredBuffers(TransportConfig.RegisteredBuffersConfig.builder()
                            .enabled(true)
                            .numBuffers(16)
                            .bufferSize(PAYLOAD_SIZE)
                            .build())
                    .sqPollEnabled(sqPoll)
                    .sqPollCpuAffinity(clientSqPollCore)
                    .build();

            TransportBackend clientBackend = TransportFactory.createBackend(clientConfig);
            clientBackend.initialize(clientConfig);
            RegisteredBufferPool clientPool = new RegisteredBufferPoolImpl(16, PAYLOAD_SIZE);
            clientBackend.registerBufferPool(clientPool);

            CountDownLatch connectLatch = new CountDownLatch(1);
            // Use clientPollerCore if specified (>= 0), otherwise fallback to clientCore
            int pollerAffinity = clientPollerCore >= 0 ? clientPollerCore : clientCore;
                client = new TcpTransport(
                    clientBackend,
                    clientPool,
                    new InetSocketAddress("127.0.0.1", port),
                    pollerAffinity,
                    bufferMode,
                    clientConfig.zeroCopySendMinBytes());
            client.start(new TransportHandlerAdapter() {
                @Override
                public void onConnected(long token) {
                    connectLatch.countDown();
                }

                @Override
                public void onDataReceived(MemorySegment data) {
                    if (tokenMode) {
                        tokenReceived = true;
                    } else {
                        if (latch != null)
                            latch.countDown();
                    }
                }

                @Override
                public void onConnectionFailed(long token, Throwable cause) {
                    if (connectLatch.getCount() > 0)
                        connectLatch.countDown();
                }

                @Override
                public void onClosed() {
                    if (latch != null)
                        latch.countDown();
                }
            });

            client.connect(new InetSocketAddress("127.0.0.1", port));
            if (!connectLatch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("Client failed to connect");
            }

            clientBuffer = client.acquireBuffer();
        }

        @Override
        public void pingPong() throws Exception {
            if (tokenMode) {
                tokenReceived = false;
                PayloadHelper.write(clientBuffer.segment());
                client.send(clientBuffer.segment());
                // Busy spin wait
                while (!tokenReceived) {
                    Thread.onSpinWait();
                }
            } else {
                latch = new CountDownLatch(1);
                PayloadHelper.write(clientBuffer.segment());
                client.send(clientBuffer.segment());
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timeout waiting for pong");
                }
            }
        }

        @Override
        public void tearDown() {
            if (client != null)
                client.close();
            if (server != null)
                server.stop();
        }
    }
}

package express.mvp.myra.server;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import express.mvp.myra.transport.BackendStats;
import express.mvp.myra.transport.CompletionHandler;
import express.mvp.myra.transport.RegisteredBuffer;
import express.mvp.myra.transport.RegisteredBufferPool;
import express.mvp.myra.transport.RegisteredBufferPoolImpl;
import express.mvp.myra.transport.TransportBackend;
import express.mvp.myra.transport.TransportConfig;
import express.mvp.myra.transport.TransportFactory;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level server API for Myra Transport.
 *
 * <p>This class provides a simple server abstraction over the low-level transport, managing the
 * accept loop, connection tracking, and I/O polling for multiple connected clients.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                       MyraServer                            │
 * ├─────────────────────────────────────────────────────────────┤
 * │                                                             │
 * │   ┌─────────────┐    ┌─────────────────────────────────┐   │
 * │   │   Accept    │    │         Connection Pool         │   │
 * │   │   Socket    │    │  ┌───────┐ ┌───────┐ ┌───────┐  │   │
 * │   └──────┬──────┘    │  │ Conn1 │ │ Conn2 │ │ Conn3 │  │   │
 * │          │           │  └───────┘ └───────┘ └───────┘  │   │
 * │          ▼           └─────────────────────────────────┘   │
 * │   ┌─────────────────────────────────────────────────────┐  │
 * │   │              Single I/O Thread                      │  │
 * │   │  • Polls server socket for accepts                  │  │
 * │   │  • Polls all client connections                     │  │
 * │   │  • Submits batched operations                       │  │
 * │   └─────────────────────────────────────────────────────┘  │
 * │                           │                                │
 * │                           ▼                                │
 * │   ┌─────────────────────────────────────────────────────┐  │
 * │   │           MyraServerHandler (callbacks)             │  │
 * │   │  • onConnect()                                      │  │
 * │   │  • onDataReceived()                                 │  │
 * │   │  • onDisconnect()                                   │  │
 * │   └─────────────────────────────────────────────────────┘  │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Token Encoding</h2>
 *
 * <p>I/O operations use 64-bit tokens with the following bit layout:
 *
 * <table border="1">
 *   <caption>Token Bit Layout</caption>
 *   <tr><th>Bits</th><th>Name</th><th>Description</th></tr>
 *   <tr><td>48-63</td><td>OP</td><td>Operation type (1=READ, 2=WRITE)</td></tr>
 *   <tr><td>16-47</td><td>CONN_ID</td><td>Connection identifier</td></tr>
 *   <tr><td>0-15</td><td>REQ_ID</td><td>Request sequence number</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * MyraServerConfig config = new MyraServerConfig()
 *     .setHost("0.0.0.0")
 *     .setPort(8080)
 *     .setNumBuffers(256)
 *     .setBufferSize(65536);
 *
 * MyraServerHandler handler = new MyraServerHandler() {
 *     @Override
 *     public void onDataReceived(TransportBackend conn,
 *             RegisteredBuffer buffer, int bytesRead) {
 *         // Echo back
 *         buffer.limit(bytesRead);
 *         conn.send(buffer, 0);
 *     }
 * };
 *
 * try (MyraServer server = new MyraServer(config, handler)) {
 *     server.start();
 *     // Server runs until stopped
 *     Thread.sleep(Long.MAX_VALUE);
 * }
 * }</pre>
 *
 * <h2>Thread Model</h2>
 *
 * <ul>
 *   <li>Single I/O thread handles all operations (accept, read, write)
 *   <li>CPU affinity can be configured for reduced latency
 *   <li>Handlers are invoked on the I/O thread (keep callbacks fast)
 * </ul>
 *
 * @see MyraServerConfig
 * @see MyraServerHandler
 */
public class MyraServer implements AutoCloseable {

    /** Server configuration. */
    private final MyraServerConfig config;

    /** User-provided event handler. */
    private final MyraServerHandler handler;

    /** Backend for the server socket (accept operations). */
    @SuppressFBWarnings(
            value = "AT_UNSAFE_RESOURCE_ACCESS_IN_THREAD",
            justification = "Backend is confined to the I/O thread; shutdown joins before cleanup.")
    private volatile TransportBackend serverBackend;

    /** Shared buffer pool for all connections. */
    private volatile RegisteredBufferPool bufferPool;

    /** List of active client connections. */
    private final List<ConnectionContext> connections = new ArrayList<>();

    /** Flag indicating whether the server is running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Latch to signal when server is ready to accept connections. */
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    /** The I/O thread that runs the event loop. */
    private Thread serverThread;

    // ─────────────────────────────────────────────────────────────────────────
    // Token encoding constants
    // ─────────────────────────────────────────────────────────────────────────

    /** Token value for accept operations. */
    private static final long TOKEN_ACCEPT = 0;

    /** Bitmask for extracting operation type from token (bits 48-63). */
    private static final long OP_MASK = 0xFFFF000000000000L;

    /** Bitmask for extracting connection ID from token (bits 16-47). */
    private static final long CONN_ID_MASK = 0x0000FFFFFFFF0000L;

    /** Bitmask for extracting request ID from token (bits 0-15). */
    private static final long REQ_ID_MASK = 0x000000000000FFFFL;

    /** Operation type: read. */
    private static final long OP_READ = 1L << 48;

    /** Operation type: write. */
    private static final long OP_WRITE = 2L << 48;

    /**
     * Creates a new server with the specified configuration and handler.
     *
     * @param config server configuration (host, port, buffer settings)
     * @param handler event handler for connection and data events
     */
    public MyraServer(MyraServerConfig config, MyraServerHandler handler) {
        this.config = config;
        this.handler = handler;
    }

    /**
     * Starts the server.
     *
     * <p>This method spawns a background I/O thread that:
     *
     * <ol>
     *   <li>Binds to the configured host:port
     *   <li>Accepts incoming connections
     *   <li>Polls for I/O events on all connections
     *   <li>Dispatches events to the handler
     * </ol>
     *
     * <p>This method returns immediately after starting the thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            serverThread = new Thread(this::runLoop, "myra-server-io");
            serverThread.start();
        }
    }

    /**
     * Waits for the server to be ready to accept connections.
     *
     * <p>This method blocks until the server has completed initialization and is ready to accept
     * incoming connections, or until the timeout expires.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if the server is ready, false if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    /**
     * Stops the server gracefully.
     *
     * <p>Signals the I/O thread to stop and waits up to 5 seconds for it to terminate. Then closes
     * all resources.
     */
    public void stop() {
        running.set(false);
        if (serverThread != null) {
            try {
                serverThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        close();
    }

    @Override
    public void close() {
        running.set(false);
        TransportBackend backend = serverBackend;
        if (backend != null) {
            try {
                backend.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (ConnectionContext ctx : connections) {
            try {
                ctx.backend.close();
            } catch (Exception e) {
                System.err.println("MyraServer: Failed to close connection backend");
                e.printStackTrace();
            }
        }
        connections.clear();
    }

    /**
     * Main event loop running on the I/O thread.
     *
     * <p>Initializes the backend, starts accepting, and polls continuously until stopped. Uses
     * busy-spin for lowest latency.
     */
    private void runLoop() {
        try {
            // Pin to CPU if configured
            if (config.getCpuAffinity() >= 0) {
                express.mvp.myra.transport.util.NativeThread.pin(config.getCpuAffinity());
            }

            initializeBackend();

            // Start accepting connections
            serverBackend.accept(TOKEN_ACCEPT);
            int initialSubmit = serverBackend.submitBatch();
            if (initialSubmit < 0) {
                System.err.println(
                        "MyraServer: submitBatch failed during startup: " + initialSubmit);
            }

            // Signal that server is ready to accept connections
            readyLatch.countDown();

            // Completion handler for all I/O events
            CompletionHandler completionHandler =
                    (token, result) -> {
                        if (token == TOKEN_ACCEPT) {
                            handleAccept(result);
                        } else {
                            handleConnectionEvent(token, result);
                        }
                    };

            // Event loop
            while (running.get()) {
                int submitted = serverBackend.submitBatch();
                if (submitted < 0) {
                    System.err.println("MyraServer: submitBatch failed in loop: " + submitted);
                }
                int count = serverBackend.poll(completionHandler);
                if (count == 0) {
                    Thread.onSpinWait(); // Busy spin for lowest latency
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Initializes the transport backend with server configuration. */
    private void initializeBackend() {
        TransportConfig transportConfig =
                TransportConfig.builder()
                        .backendType(TransportConfig.BackendType.IO_URING)
                        .registeredBuffers(
                                TransportConfig.RegisteredBuffersConfig.builder()
                                        .enabled(true)
                                        .numBuffers(config.getNumBuffers())
                                        .bufferSize(config.getBufferSize())
                                        .build())
                        .cpuAffinity(config.getCpuAffinity())
                        .sqPollCpuAffinity(config.getSqPollCpuAffinity())
                        .sqPollEnabled(config.isSqPollEnabled())
                        .sqPollIdleTimeout(config.getSqPollIdleTimeout())
                        .build();

        serverBackend = TransportFactory.createBackend(transportConfig);
        serverBackend.initialize(transportConfig);

        bufferPool = new RegisteredBufferPoolImpl(config.getNumBuffers(), config.getBufferSize());
        serverBackend.registerBufferPool(bufferPool);

        serverBackend.bind(new InetSocketAddress(config.getHost(), config.getPort()));
    }

    /**
     * Handles an accept completion event.
     *
     * @param result the file descriptor of accepted connection, or negative error
     */
    private void handleAccept(long result) {
        if (result < 0) {
            System.err.println("MyraServer: Accept failed with result: " + result);
            return;
        }

        try {
            // Create backend for the new connection
            TransportBackend clientBackend = serverBackend.createFromAccepted((int) result);
            clientBackend.registerBufferPool(bufferPool);

            // Track the connection
            ConnectionContext ctx = new ConnectionContext(clientBackend);
            connections.add(ctx);

            // Notify handler
            handler.onConnect(ctx.wrapper);

            // Start reading from this connection
            startRead(ctx);

            // Re-arm accept for next connection
            serverBackend.accept(TOKEN_ACCEPT);
            int submitted = serverBackend.submitBatch();
            if (submitted < 0) {
                System.err.println("MyraServer: submitBatch failed after accept: " + submitted);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts a read operation on the given connection.
     *
     * @param ctx the connection context
     */
    private void startRead(ConnectionContext ctx) {
        RegisteredBuffer buf = bufferPool.acquire();
        ctx.currentReadBuffer = buf;
        ctx.backend.receive(buf, OP_READ | (ctx.id << 16));
    }

    /**
     * Handles read/write completion events for client connections.
     *
     * @param token the operation token (encodes op type, conn ID, req ID)
     * @param result bytes transferred, or negative error code
     */
    private void handleConnectionEvent(long token, long result) {
        // Decode token
        long op = token & OP_MASK;
        long connId = (token & CONN_ID_MASK) >>> 16;
        long reqId = token & REQ_ID_MASK;

        // Find the connection
        ConnectionContext ctx = null;
        for (ConnectionContext c : connections) {
            if (c.id == connId) {
                ctx = c;
                break;
            }
        }

        if (ctx == null) {
            return; // Connection already closed
        }

        if (op == OP_READ) {
            // Read completion
            if (result <= 0) {
                closeConnection(ctx);
                return;
            }

            // Notify handler with received data
            handler.onDataReceived(ctx.wrapper, ctx.currentReadBuffer, (int) result);

            // Start next read
            try {
                startRead(ctx);
            } catch (Exception e) {
                e.printStackTrace();
                closeConnection(ctx);
            }

        } else if (op == OP_WRITE) {
            // Write completion - release the buffer
            int index = (int) (reqId & ConnectionContext.PENDING_WRITES_MASK);
            RegisteredBuffer buf = ctx.pendingWrites[index];
            ctx.pendingWrites[index] = null;

            if (buf != null) {
                buf.close();
            }
        }
    }

    /**
     * Closes a connection and notifies the handler.
     *
     * @param ctx the connection context to close
     */
    private void closeConnection(ConnectionContext ctx) {
        handler.onDisconnect(ctx.wrapper);
        try {
            ctx.backend.close();
        } catch (Exception e) {
            System.err.println("MyraServer: Failed to close client backend");
            e.printStackTrace();
        }
    }

    /**
     * Context for tracking a single client connection.
     *
     * <p>Each connection has a unique ID, its own backend, and tracks pending write operations for
     * buffer lifecycle management.
     */
    private static class ConnectionContext {
        /** Connection ID generator. */
        static final AtomicLong ID_GEN = new AtomicLong(0);

        /** Unique identifier for this connection. */
        final long id;

        /** The transport backend for this connection. */
        final TransportBackend backend;

        /** Wrapper backend exposed to the handler (manages tokens). */
        final TransportBackend wrapper;

        /** Current buffer being used for read operations. */
        RegisteredBuffer currentReadBuffer;

        /** Generator for request IDs. */
        long reqIdGen = 0;

        /** Size of the pending writes circular buffer. */
        private static final int PENDING_WRITES_SIZE = 4096;

        /** Mask for converting request ID to array index. */
        private static final int PENDING_WRITES_MASK = PENDING_WRITES_SIZE - 1;

        /** Circular buffer tracking buffers pending write completion. */
        final RegisteredBuffer[] pendingWrites = new RegisteredBuffer[PENDING_WRITES_SIZE];

        /**
         * Creates a new connection context.
         *
         * @param backend the transport backend for this connection
         */
        ConnectionContext(TransportBackend backend) {
            this.id = ID_GEN.incrementAndGet();
            this.backend = backend;
            this.wrapper = new TransportBackendWrapper(backend, this);
        }
    }

    /**
     * Wrapper around TransportBackend that manages token encoding for the handler.
     *
     * <p>This wrapper intercepts send operations to encode proper tokens and track pending write
     * buffers for cleanup.
     */
    private static class TransportBackendWrapper implements TransportBackend {
        private final TransportBackend delegate;
        private final ConnectionContext ctx;

        TransportBackendWrapper(TransportBackend delegate, ConnectionContext ctx) {
            this.delegate = delegate;
            this.ctx = ctx;
        }

        @Override
        public void send(RegisteredBuffer buffer, long token) {
            // Encode our own token with connection and request IDs
            long reqId = ++ctx.reqIdGen;
            long myToken = OP_WRITE | (ctx.id << 16) | (reqId & 0xFFFF);

            // Track buffer for cleanup on completion
            int index = (int) (reqId & ConnectionContext.PENDING_WRITES_MASK);
            ctx.pendingWrites[index] = buffer;

            delegate.send(buffer, myToken);
        }

        @Override
        public void send(MemorySegment data, int length, long token) {
            delegate.send(data, length, token);
        }

        // ─────────────────────────────────────────────────────────────────────
        // Delegate methods (pass through to underlying backend)
        // ─────────────────────────────────────────────────────────────────────

        @Override
        public void initialize(TransportConfig config) {
            delegate.initialize(config);
        }

        @Override
        public void registerBufferPool(RegisteredBufferPool pool) {
            delegate.registerBufferPool(pool);
        }

        @Override
        public void bind(SocketAddress address) {
            delegate.bind(address);
        }

        @Override
        public void accept(long token) {
            delegate.accept(token);
        }

        @Override
        public void connect(SocketAddress address, long token) {
            delegate.connect(address, token);
        }

        @Override
        public void receive(RegisteredBuffer buffer, long token) {
            delegate.receive(buffer, token);
        }

        @Override
        public void receive(MemorySegment buffer, int length, long token) {
            delegate.receive(buffer, length, token);
        }

        @Override
        public int poll(CompletionHandler handler) {
            return delegate.poll(handler);
        }

        @Override
        public int waitForCompletion(long timeoutMillis, CompletionHandler handler) {
            return delegate.waitForCompletion(timeoutMillis, handler);
        }

        @Override
        public int submitBatch() {
            return delegate.submitBatch();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public boolean supportsRegisteredBuffers() {
            return delegate.supportsRegisteredBuffers();
        }

        @Override
        public boolean supportsBatchSubmission() {
            return delegate.supportsBatchSubmission();
        }

        @Override
        public boolean supportsTLS() {
            return delegate.supportsTLS();
        }

        @Override
        public String getBackendType() {
            return delegate.getBackendType();
        }

        @Override
        public BackendStats getStats() {
            return delegate.getStats();
        }

        @Override
        public TransportBackend createFromAccepted(int handle) {
            return delegate.createFromAccepted(handle);
        }
    }
}

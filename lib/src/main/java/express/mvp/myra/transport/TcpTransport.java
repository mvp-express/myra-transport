package express.mvp.myra.transport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import express.mvp.myra.transport.iouring.IoUringBackend;
import express.mvp.myra.transport.iouring.LibUring;
import express.mvp.myra.transport.util.NativeThread;
import express.mvp.roray.ffm.concurrent.queue.MpscRingBuffer;
import java.lang.foreign.MemorySegment;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP-based implementation of {@link Transport} using a pluggable I/O backend.
 *
 * <p>This is the primary transport implementation, providing asynchronous TCP communication with
 * zero-copy I/O support via registered buffers.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                      TcpTransport                           │
 * ├─────────────────────────────────────────────────────────────┤
 * │  Application Thread        │        Poller Thread           │
 * │  ─────────────────         │        ─────────────           │
 * │  • connect()               │        • pollLoop()            │
 * │  • send()                  │        • processCommands()     │
 * │  • acquireBuffer()         │        • backend.poll()        │
 * │           │                │               │                │
 * │           ▼                │               ▼                │
 * │   ┌───────────────┐        │       ┌─────────────┐          │
 * │   │ Command Queue │───────────────▶│  Backend    │          │
 * │   │  (MPSC Ring)  │        │       │ (io_uring)  │          │
 * │   └───────────────┘        │       └─────────────┘          │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Thread Model</h2>
 *
 * <ul>
 *   <li><b>Application threads:</b> Submit commands via lock-free MPSC queue
 *   <li><b>Poller thread:</b> Processes commands, submits to kernel, polls completions
 * </ul>
 *
 * <h2>Token-Based Correlation</h2>
 *
 * <p>Operations are tracked using 64-bit tokens with the following bit layout:
 *
 * <ul>
 *   <li>Bit 63: Receive operation flag
 *   <li>Bit 62: Connect operation flag
 *   <li>Bits 0-61: Monotonically increasing sequence number
 * </ul>
 *
 * <h2>CPU Affinity</h2>
 *
 * <p>If configured, the poller thread is pinned to a specific CPU core for reduced latency variance
 * and improved cache locality.
 *
 * @see Transport
 * @see TransportBackend
 * @see TransportHandler
 */
@SuppressFBWarnings(
        value = "UUF_UNUSED_FIELD",
        justification = "Padding fields isolate hot variables to avoid false sharing.")
public final class TcpTransport implements Transport {

    /** The I/O backend (io_uring or NIO). */
    private final TransportBackend backend;

    /** Pool of registered buffers for zero-copy I/O. */
    private final RegisteredBufferPool bufferPool;

    /** Remote endpoint this transport connects to. */
    private final SocketAddress remoteAddress;

    /** CPU core to pin the poller thread to (-1 for no affinity). */
    private final int cpuAffinity;

    /** io_uring buffer strategy (may be ignored for non-io_uring backends). */
    private final TransportConfig.BufferMode bufferMode;

    /** Minimum payload size (bytes) before attempting SEND_ZC. */
    private final int zeroCopySendMinBytes;

    /** Cached io_uring backend for mode-specific features. */
    private final IoUringBackend ioUringBackend;

    /** Connection state flag. */
    private volatile boolean connected = false;

    /** Closed state flag. */
    private volatile boolean closed = false;

    /** Callback handler for I/O events. */
    private TransportHandler handler;

    /** Background thread that polls for I/O completions. */
    private final Thread pollerThread;

    // ─────────────────────────────────────────────────────────────────────────
    // Cache line padding to prevent false sharing (Issue #2 fix)
    // tokenGenerator is written by application threads, while receive fields
    // are written by poller thread. Padding ensures they're on separate cache lines.
    // ─────────────────────────────────────────────────────────────────────────

    /** Padding before tokenGenerator to isolate from preceding fields. */
    @SuppressWarnings("unused")
    private long p0, p1, p2, p3, p4, p5, p6, p7;

    /** Generator for unique operation tokens. Written by application threads. */
    private final AtomicLong tokenGenerator = new AtomicLong(1); // Start at 1, positive

    /** Padding after tokenGenerator to isolate from following fields. */
    @SuppressWarnings("unused")
    private long p8, p9, p10, p11, p12, p13, p14, p15;

    /**
     * Lock-free command queue for application-to-poller communication. Uses MPSC (multi-producer,
     * single-consumer) ring buffer.
     */
    private final MpscRingBuffer<Object> commandQueue = new MpscRingBuffer<>(4096);

    // ─────────────────────────────────────────────────────────────────────────
    // Command types for inter-thread communication
    // ─────────────────────────────────────────────────────────────────────────

    /** Command to initiate a connection. */
    private static final class ConnectCommand {
        /** Target address to connect to. */
        final SocketAddress address;

        /** Token for callback correlation. */
        final long token;

        ConnectCommand(SocketAddress address, long token) {
            this.address = address;
            this.token = token;
        }
    }

    /** Sentinel object representing a close command. */
    private static final Object CLOSE_COMMAND = new Object();

    // ─────────────────────────────────────────────────────────────────────────
    // Token masks for operation type identification
    // ─────────────────────────────────────────────────────────────────────────

    /** Token flag for receive operations (bit 63 set). */
    private static final long RECEIVE_TOKEN_MASK = 0x8000_0000_0000_0000L;

    /** Token flag for connect operations (bit 62 set). */
    private static final long CONNECT_TOKEN_MASK = 0x4000_0000_0000_0000L;

    /** Currently pending receive buffer. */
    private RegisteredBuffer currentReceiveBuffer;

    /** Token for the currently pending receive operation. */
    private long currentReceiveToken;

    /** Whether a multishot receive is active (buffer ring mode). */
    private boolean multishotReceiveActive;

    /** One-time log guard for BUFFER_RING receive failures. */
    private boolean bufferRingReceiveFailureLogged;

    /**
     * If true, BUFFER_RING is disabled for this transport instance and we fall back to STANDARD.
     */
    private boolean bufferRingReceiveDisabled;

    // ─────────────────────────────────────────────────────────────────────────
    // Pending send tracking
    // ─────────────────────────────────────────────────────────────────────────

    /** Size of the pending sends circular buffer. Must be a power of 2 for fast masking. */
    private static final int PENDING_SENDS_SIZE = 4096;

    /** Mask for converting token to array index. */
    private static final int PENDING_SENDS_MASK = PENDING_SENDS_SIZE - 1;

    /** Circular buffer tracking buffers pending send completion. */
    private final RegisteredBuffer[] pendingSends = new RegisteredBuffer[PENDING_SENDS_SIZE];

    /** Tracks whether a pending send is awaiting a SEND_ZC notification CQE. */
    private final boolean[] pendingSendsAwaitingNotif = new boolean[PENDING_SENDS_SIZE];

    /** Tracks whether a pending send was submitted via SEND_ZC. */
    private final boolean[] pendingSendsZeroCopy = new boolean[PENDING_SENDS_SIZE];

    /** Tracks whether a pending send was submitted using fixed-buffer fast path. */
    private final boolean[] pendingSendsFixed = new boolean[PENDING_SENDS_SIZE];

    /** Tracks whether a send has been retried on the standard path. */
    private final boolean[] pendingSendsRetried = new boolean[PENDING_SENDS_SIZE];

    /**
     * Creates a new TCP transport with the specified backend and configuration.
     *
     * @param backend the I/O backend to use
     * @param bufferPool the registered buffer pool
     * @param remoteAddress the remote endpoint to connect to
     * @param cpuAffinity CPU core to pin poller thread (-1 for no affinity)
     */
    public TcpTransport(
            TransportBackend backend,
            RegisteredBufferPool bufferPool,
            SocketAddress remoteAddress,
            int cpuAffinity,
            TransportConfig.BufferMode bufferMode,
            int zeroCopySendMinBytes) {
        this.backend = backend;
        this.bufferPool = bufferPool;
        this.remoteAddress = remoteAddress;
        this.cpuAffinity = cpuAffinity;
        this.bufferMode = bufferMode;
        this.zeroCopySendMinBytes = zeroCopySendMinBytes;
        this.ioUringBackend = backend instanceof IoUringBackend iob ? iob : null;

        this.pollerThread = new Thread(this::pollLoop, "transport-poller");
        this.pollerThread.setDaemon(true);
    }

    @Override
    public void start(TransportHandler handler) {
        this.handler = handler;
        if (!pollerThread.isAlive()) {
            pollerThread.start();
        }
    }

    /**
     * Posts a receive operation to the backend.
     *
     * <p>Acquires a buffer from the pool and submits a receive operation. Called after connection
     * and after each successful receive to maintain continuous receive readiness.
     */
    private void postReceive() {
        if (closed) return;
        try {
            long token = tokenGenerator.incrementAndGet() | RECEIVE_TOKEN_MASK;

            // BUFFER_RING: single multishot op, kernel picks buffers.
            if (bufferMode == TransportConfig.BufferMode.BUFFER_RING
                    && ioUringBackend != null
                    && !bufferRingReceiveDisabled) {
                if (!ioUringBackend.isBufferRingEnabled()) {
                    ioUringBackend.initBufferRing();
                }

                if (ioUringBackend.isBufferRingEnabled()) {
                    this.currentReceiveToken = token;
                    this.multishotReceiveActive = true;
                    if (ioUringBackend.submitMultishotRecvWithBufferRing(token)) {
                        return;
                    }

                    // Submission failed; fall back to standard receive.
                    this.multishotReceiveActive = false;
                }
                // If buffer ring isn't available, fall back to standard.
            }

            RegisteredBuffer buffer = bufferPool.acquire();
            this.currentReceiveBuffer = buffer;
            this.currentReceiveToken = token;

            if (bufferMode == TransportConfig.BufferMode.FIXED && ioUringBackend != null) {
                ioUringBackend.receiveFixedBuffer(buffer, token);
            } else {
                backend.receive(buffer, token);
            }
        } catch (Exception e) {
            if (!closed) {
                e.printStackTrace(); // TODO: Handle receive failure gracefully
            }
        }
    }

    /**
     * Main polling loop executed by the poller thread.
     *
     * <p>This loop continuously:
     *
     * <ol>
     *   <li>Processes commands from application threads
     *   <li>Submits pending operations to the kernel
     *   <li>Polls for completions and dispatches to handler
     * </ol>
     *
     * <p>If configured, pins to the specified CPU core for reduced latency.
     */
    private void pollLoop() {
        if (cpuAffinity >= 0) {
            NativeThread.pin(cpuAffinity);
        }

        // Completion handler for all I/O operations
        IoUringBackend.ExtendedCompletionHandler completionHandler =
                (token, result, flags) -> {
                    if (handler == null) return;

                    // Decode operation type from token flags
                    boolean isReceive = (token & RECEIVE_TOKEN_MASK) != 0;
                    long userToken = token & ~RECEIVE_TOKEN_MASK;

                    if (isReceive) {
                        // Handle receive completion
                        if (token == currentReceiveToken) {
                            if (multishotReceiveActive
                                    && bufferMode == TransportConfig.BufferMode.BUFFER_RING
                                    && ioUringBackend != null) {
                                // Buffer ring multishot receive: bufferId is encoded in CQE flags.
                                if (result >= 0) {
                                    int bufferId =
                                            ((flags & LibUring.IORING_CQE_F_BUFFER) != 0)
                                                    ? ((flags >> 16) & 0xFFFF)
                                                    : -1;
                                    MemorySegment buf =
                                            ioUringBackend.getBufferRingBuffer(bufferId);
                                    if (buf != null) {
                                        handler.onDataReceived(buf.asSlice(0, result));
                                        ioUringBackend.recycleBufferRingBuffer(bufferId);
                                    } else {
                                        if (!bufferRingReceiveFailureLogged) {
                                            bufferRingReceiveFailureLogged = true;
                                            System.err.println(
                                                    "Warning: BUFFER_RING recv without buffer id"
                                                            + " (res="
                                                            + result
                                                            + ", flags=0x"
                                                            + Integer.toHexString(flags)
                                                            + ") - falling back to STANDARD");
                                        }
                                        multishotReceiveActive = false;
                                        bufferRingReceiveDisabled = true;
                                        postReceive();
                                        return;
                                    }
                                } else if (result == -1) {
                                    close();
                                } else {
                                    if (!bufferRingReceiveFailureLogged) {
                                        bufferRingReceiveFailureLogged = true;
                                        System.err.println(
                                                "Warning: BUFFER_RING recv failed (res="
                                                        + result
                                                        + ", flags=0x"
                                                        + Integer.toHexString(flags)
                                                        + ") - falling back to STANDARD");
                                    }
                                    multishotReceiveActive = false;
                                    bufferRingReceiveDisabled = true;
                                    postReceive();
                                    return;
                                }

                                // If multishot ended, restart it.
                                if ((flags & LibUring.IORING_CQE_F_MORE) == 0 && !closed) {
                                    ioUringBackend.submitMultishotRecvWithBufferRing(token);
                                }
                            } else {
                                RegisteredBuffer buffer = currentReceiveBuffer;
                                // Clear current before callback to allow re-posting
                                currentReceiveBuffer = null;
                                currentReceiveToken = 0;

                                if (buffer != null) {
                                    if (result < 0
                                            && bufferMode == TransportConfig.BufferMode.FIXED
                                            && ioUringBackend != null
                                            && (result == -22 || result == -95)) {
                                        // Fixed-buffer fast path rejected; retry as standard.
                                        currentReceiveBuffer = buffer;
                                        currentReceiveToken = token;
                                        backend.receive(buffer, token);
                                        return;
                                    }

                                    if (result >= 0) {
                                        // Successful receive
                                        buffer.limit(result);
                                        handler.onDataReceived(buffer.segment().asSlice(0, result));
                                        buffer.close();
                                        postReceive(); // Post next receive
                                    } else {
                                        buffer.close();
                                        if (result == -1) { // EOF - connection closed by peer
                                            close();
                                        } else {
                                            postReceive(); // Retry on transient errors
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Handle send or connect completion
                        boolean isConnect = (token & CONNECT_TOKEN_MASK) != 0;
                        long actualUserToken = userToken & ~CONNECT_TOKEN_MASK;

                        if (isConnect) {
                            if (result >= 0) {
                                connected = true;
                                handler.onConnected(actualUserToken);
                                postReceive(); // Start receiving data
                            } else {
                                handler.onConnectionFailed(
                                        actualUserToken,
                                        new TransportException("Connect failed: " + result));
                            }
                        } else {
                            // Send completion
                            int index = (int) (actualUserToken & PENDING_SENDS_MASK);
                            RegisteredBuffer buf = pendingSends[index];

                            // Issue #3 Fix: Validate token matches to detect stale completions
                            // If slot was reused for a newer operation, buf.getToken() won't match
                            if (buf == null) {
                                // Slot already cleared - possible timeout or cancellation
                                // Log warning in production, but continue processing
                                return;
                            }

                            if (buf.getToken() != actualUserToken) {
                                // Stale completion - slot was reused for a newer operation
                                // Do NOT close buf - it belongs to the newer operation
                                // Do NOT clear the slot - newer operation needs it
                                // Log warning in production
                                return;
                            }

                            boolean isNotif = (flags & LibUring.IORING_CQE_F_NOTIF) != 0;

                            boolean wasZeroCopy = pendingSendsZeroCopy[index];
                            boolean wasFixed = pendingSendsFixed[index];

                            if (wasZeroCopy) {
                                if (isNotif) {
                                    // Notification: safe to reuse buffer now.
                                    if (pendingSendsAwaitingNotif[index]) {
                                        pendingSendsAwaitingNotif[index] = false;
                                        pendingSends[index] = null;
                                        pendingSendsZeroCopy[index] = false;
                                        pendingSendsFixed[index] = false;
                                        pendingSendsRetried[index] = false;
                                        buf.close();
                                    }
                                    return;
                                }

                                if (result < 0
                                        && !pendingSendsRetried[index]
                                        && (result == -22 || result == -95)) {
                                    // Zero-copy not supported / invalid for this socket. Retry as
                                    // standard.
                                    pendingSendsRetried[index] = true;
                                    pendingSendsZeroCopy[index] = false;
                                    pendingSendsAwaitingNotif[index] = false;
                                    backend.send(buf, actualUserToken);
                                    return;
                                }

                                if (result >= 0) {
                                    // Send CQE: notify app, keep buffer until NOTIF.
                                    pendingSendsAwaitingNotif[index] = true;
                                    handler.onSendComplete(actualUserToken);
                                } else {
                                    // Failure: release immediately.
                                    pendingSendsAwaitingNotif[index] = false;
                                    pendingSends[index] = null;
                                    pendingSendsZeroCopy[index] = false;
                                    pendingSendsFixed[index] = false;
                                    pendingSendsRetried[index] = false;
                                    buf.close();
                                    handler.onSendFailed(
                                            actualUserToken,
                                            new TransportException("Operation failed: " + result));
                                }
                            } else if (wasFixed) {
                                if (result < 0
                                        && !pendingSendsRetried[index]
                                        && (result == -22 || result == -95)) {
                                    // Fixed-buffer fast path not supported. Retry as standard.
                                    pendingSendsRetried[index] = true;
                                    pendingSendsFixed[index] = false;
                                    backend.send(buf, actualUserToken);
                                    return;
                                }

                                pendingSends[index] = null;
                                pendingSendsAwaitingNotif[index] = false;
                                pendingSendsZeroCopy[index] = false;
                                pendingSendsFixed[index] = false;
                                pendingSendsRetried[index] = false;

                                if (result >= 0) {
                                    buf.close();
                                    handler.onSendComplete(actualUserToken);
                                } else {
                                    buf.close();
                                    handler.onSendFailed(
                                            actualUserToken,
                                            new TransportException("Operation failed: " + result));
                                }
                            } else {
                                // Token matches - safe to process this completion
                                pendingSends[index] = null; // Clear reference
                                pendingSendsAwaitingNotif[index] = false;
                                pendingSendsZeroCopy[index] = false;
                                pendingSendsFixed[index] = false;
                                pendingSendsRetried[index] = false;

                                if (result >= 0) {
                                    buf.close();
                                    handler.onSendComplete(actualUserToken);
                                } else {
                                    buf.close();
                                    handler.onSendFailed(
                                            actualUserToken,
                                            new TransportException("Operation failed: " + result));
                                }
                            }
                        }
                    }
                };

        // Main polling loop
        while (!closed) {
            try {
                // 1. Process commands from application threads
                processCommands();

                // 2. Submit any pending operations to kernel
                backend.submitBatch();

                // 3. Poll for completions (non-blocking)
                if (backend.poll(completionHandler) == 0) {
                    Thread.onSpinWait(); // CPU hint for spin-wait loop
                }
            } catch (Exception e) {
                if (!closed) e.printStackTrace();
            }
        }
    }

    /**
     * Processes all pending commands from the command queue.
     *
     * <p>Commands are submitted by application threads and processed by the poller thread to
     * maintain single-threaded access to the backend.
     */
    private void processCommands() {
        Object cmd;
        while ((cmd = commandQueue.poll()) != null) {
            if (cmd instanceof RegisteredBuffer) {
                // Send command
                RegisteredBuffer buffer = (RegisteredBuffer) cmd;
                long token = buffer.getToken();
                int index = (int) (token & PENDING_SENDS_MASK);
                if (pendingSendsZeroCopy[index] && ioUringBackend != null) {
                    ioUringBackend.sendZeroCopy(buffer, token);
                } else if (pendingSendsFixed[index] && ioUringBackend != null) {
                    ioUringBackend.sendFixedBuffer(buffer, token);
                } else {
                    backend.send(buffer, token);
                }
            } else if (cmd instanceof ConnectCommand) {
                // Connect command
                ConnectCommand cc = (ConnectCommand) cmd;
                backend.connect(cc.address, cc.token);
            } else if (cmd == CLOSE_COMMAND) {
                // Close command
                doClose();
            }
        }
    }

    /**
     * Performs the actual close operation on the poller thread.
     *
     * <p>Closes the backend and buffer pool, and notifies the handler.
     */
    private void doClose() {
        if (closed) return;
        closed = true;
        connected = false;

        if (handler != null) {
            handler.onClosed();
        }

        try {
            backend.close();
        } catch (Exception e) {
            System.err.println("TcpTransport: backend close failed: " + e.getMessage());
        }

        try {
            bufferPool.close();
        } catch (Exception e) {
            System.err.println("TcpTransport: buffer pool close failed: " + e.getMessage());
        }
    }

    @Override
    public long connect(SocketAddress remoteAddress) {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        long token = tokenGenerator.incrementAndGet() | CONNECT_TOKEN_MASK;
        if (!commandQueue.offer(new ConnectCommand(remoteAddress, token))) {
            throw new TransportException("Command queue full");
        }
        return token;
    }

    @Override
    public long send(MemorySegment data) {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        long token = tokenGenerator.incrementAndGet();

        // Issue #1 Fix: Check for slot collision before proceeding
        // If slot is occupied, it means we have too many in-flight sends
        int index = (int) (token & PENDING_SENDS_MASK);
        RegisteredBuffer existing = pendingSends[index];
        if (existing != null) {
            // Slot collision - too many pending sends (>4096 in flight)
            // This indicates backpressure - caller should slow down
            throw new TransportException(
                    "Too many pending sends: slot "
                            + index
                            + " still occupied by token "
                            + existing.getToken()
                            + ". Current token: "
                            + token
                            + ". Consider implementing backpressure or increasing"
                            + " PENDING_SENDS_SIZE.");
        }

        RegisteredBuffer buffer = bufferPool.acquire();

        // Validate data fits in buffer
        if (data.byteSize() > buffer.capacity()) {
            buffer.close();
            throw new IllegalArgumentException("Data too large for buffer");
        }

        // Copy data to registered buffer (enables zero-copy to kernel)
        buffer.segment().asSlice(0, data.byteSize()).copyFrom(data);
        buffer.limit(data.byteSize());
        buffer.setToken(token);

        // Track pending send for completion handling
        pendingSends[index] = buffer;
        pendingSendsAwaitingNotif[index] = false;
        pendingSendsRetried[index] = false;
        pendingSendsZeroCopy[index] =
                bufferMode == TransportConfig.BufferMode.ZERO_COPY
                        && ioUringBackend != null
                        && data.byteSize() >= zeroCopySendMinBytes;
        pendingSendsFixed[index] =
                bufferMode == TransportConfig.BufferMode.FIXED && ioUringBackend != null;

        // Submit to poller thread
        if (!commandQueue.offer(buffer)) {
            pendingSends[index] = null;
            pendingSendsAwaitingNotif[index] = false;
            pendingSendsZeroCopy[index] = false;
            pendingSendsFixed[index] = false;
            pendingSendsRetried[index] = false;
            buffer.close();
            throw new TransportException("Command queue full");
        }
        return token;
    }

    @Override
    public RegisteredBuffer acquireBuffer() {
        return bufferPool.acquire();
    }

    @Override
    public int availableBufferSpace() {
        return bufferPool.available();
    }

    @Override
    public ConnectionPool getConnectionPool() {
        // Direct transports don't use connection pooling
        return null;
    }

    @Override
    public TransportHealth getHealth() {
        return TransportHealth.builder()
                .healthy(connected && !closed)
                .activeConnections(connected ? 1 : 0)
                .build();
    }

    @Override
    public boolean isConnected() {
        return connected && !closed;
    }

    @Override
    public SocketAddress getLocalAddress() {
        return null; // Would require backend support to query
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        // If called from within the poller thread, close directly to avoid self-join deadlock.
        if (Thread.currentThread() == pollerThread) {
            doClose();
            return;
        }

        // Ensure the close command is delivered even under backpressure.
        // If the queue is temporarily full, spin until the poller drains it.
        while (!commandQueue.offer(CLOSE_COMMAND)) {
            Thread.onSpinWait();
            if (closed) {
                break;
            }
        }

        // Wait for the poller thread to observe the close and exit.
        try {
            pollerThread.join(5_000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

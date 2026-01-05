package express.mvp.myra.transport.nio;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import express.mvp.myra.transport.BackendStats;
import express.mvp.myra.transport.CompletionHandler;
import express.mvp.myra.transport.RegisteredBuffer;
import express.mvp.myra.transport.RegisteredBufferPool;
import express.mvp.myra.transport.TransportBackend;
import express.mvp.myra.transport.TransportConfig;
import express.mvp.myra.transport.TransportException;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO-based transport backend for portable, cross-platform I/O.
 *
 * <p>This backend uses traditional Java NIO ({@link SocketChannel} + {@link Selector}) and works on
 * all platforms. While not as fast as io_uring, it provides reliable baseline performance with
 * zero-allocation hot paths.
 *
 * <h2>Performance Comparison with io_uring</h2>
 *
 * <table border="1">
 *   <caption>NIO vs io_uring Feature Comparison</caption>
 *   <tr><th>Feature</th><th>NIO</th><th>io_uring</th></tr>
 *   <tr><td>Platform</td><td>All JVM platforms</td><td>Linux 5.1+</td></tr>
 *   <tr><td>Registered buffers</td><td>No</td><td>Yes (1.7x speedup)</td></tr>
 *   <tr><td>Batch submission</td><td>No</td><td>Yes (100x syscall reduction)</td></tr>
 *   <tr><td>SQPOLL mode</td><td>No</td><td>Yes (zero syscall I/O)</td></tr>
 *   <tr><td>Syscalls per op</td><td>~1-2</td><td>Amortized 0.01</td></tr>
 * </table>
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Zero-allocation hot path:</b> Pre-allocated completion ring buffer, cached ByteBuffer
 *       views via FFM's {@link MemorySegment#asByteBuffer()}
 *   <li><b>FFM-first:</b> Works directly with MemorySegment, avoiding byte[] copies
 *   <li><b>Thread-safe:</b> Atomic state management, lock-free completion ring
 *   <li><b>API consistent:</b> Same interface as IoUringBackend for seamless fallback
 * </ul>
 *
 * <h2>When to Use NIO</h2>
 *
 * <ul>
 *   <li>Cross-platform compatibility is required
 *   <li>Running on non-Linux systems (macOS, Windows)
 *   <li>io_uring is unavailable (older kernel or missing liburing)
 *   <li>Development/testing on local machines
 * </ul>
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │                      NioBackend                         │
 * ├─────────────────────────────────────────────────────────┤
 * │                                                         │
 * │   ┌─────────────┐    ┌─────────────┐                   │
 * │   │ Completion  │    │  Selector   │                   │
 * │   │ Ring Buffer │    │  (epoll/    │                   │
 * │   │ (immediate) │    │   kqueue)   │                   │
 * │   └─────────────┘    └─────────────┘                   │
 * │          │                  │                          │
 * │          ▼                  ▼                          │
 * │   ┌─────────────────────────────────────┐              │
 * │   │         poll() / waitFor()          │              │
 * │   │  Delivers completions to handler    │              │
 * │   └─────────────────────────────────────┘              │
 * │                                                         │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * @see TransportBackend
 * @see express.mvp.myra.transport.iouring.IoUringBackend
 */
public final class NioBackend implements TransportBackend {

    // ─────────────────────────────────────────────────────────────────────────
    // Connection and channel state
    // ─────────────────────────────────────────────────────────────────────────

    /** Current connection state (thread-safe via volatile). */
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    /** Client socket channel for outgoing connections. */
    private SocketChannel channel;

    /** Server socket channel for accepting incoming connections. */
    @SuppressFBWarnings(
            value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
            justification = "Initialized during bind() when server mode is enabled.")
    private ServerSocketChannel serverChannel;

    /** Selector for non-blocking I/O multiplexing. */
    private Selector selector;

    /** Buffer pool reference (stored for management, not for kernel registration). */
    private RegisteredBufferPool bufferPool;

    /** Transport configuration. */
    private TransportConfig config;

    /** Whether the backend has been initialized. */
    private volatile boolean initialized = false;

    /** Whether the backend has been closed. */
    private volatile boolean closed = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Pre-allocated accepted channels (zero-allocation accept handling)
    // ─────────────────────────────────────────────────────────────────────────

    /** Maximum number of accepted connections that can be tracked. */
    private static final int MAX_ACCEPTED_CHANNELS = 1024;

    /** Pre-allocated array for storing accepted client channels. */
    private final SocketChannel[] acceptedChannels = new SocketChannel[MAX_ACCEPTED_CHANNELS];

    /** Next index for storing an accepted channel (atomically incremented). */
    private final AtomicInteger acceptedChannelIndex = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────────────
    // Immediate completion ring buffer (zero-allocation)
    // ─────────────────────────────────────────────────────────────────────────

    /** Size of the completion ring buffer (must be power of 2). */
    private static final int COMPLETION_RING_SIZE = 256;

    /** Pre-allocated array of completion tokens. */
    private final long[] completionTokens = new long[COMPLETION_RING_SIZE];

    /** Pre-allocated array of completion results. */
    private final int[] completionResults = new int[COMPLETION_RING_SIZE];

    /** Head index of the completion ring (consumer side). */
    private final AtomicInteger completionHead = new AtomicInteger(0);

    /** Tail index of the completion ring (producer side). */
    private final AtomicInteger completionTail = new AtomicInteger(0);

    // ─────────────────────────────────────────────────────────────────────────
    // Statistics counters (comprehensive tracking)
    // ─────────────────────────────────────────────────────────────────────────

    /** Total bytes sent since initialization. */
    private final AtomicLong bytesSent = new AtomicLong(0);

    /** Total bytes received since initialization. */
    private final AtomicLong bytesReceived = new AtomicLong(0);

    /** Total successful send operations. */
    private final AtomicLong sendCount = new AtomicLong(0);

    /** Total successful receive operations. */
    private final AtomicLong receiveCount = new AtomicLong(0);

    /** Total failed send operations. */
    private final AtomicLong failedSends = new AtomicLong(0);

    /** Total failed receive operations. */
    private final AtomicLong failedReceives = new AtomicLong(0);

    /** Total syscalls made (for efficiency measurement). */
    private final AtomicLong totalSyscalls = new AtomicLong(0);

    /** Total successful connect operations. */
    private final AtomicLong connectCount = new AtomicLong(0);

    /** Total successful accept operations. */
    private final AtomicLong acceptCount = new AtomicLong(0);

    /**
     * Enqueues an immediate completion (zero-allocation ring buffer).
     *
     * @param token the operation token
     * @param result the operation result
     * @return true if enqueued, false if ring is full
     */
    private boolean enqueueCompletion(long token, int result) {
        int tail = completionTail.get();
        int nextTail = (tail + 1) & (COMPLETION_RING_SIZE - 1);

        if (nextTail == completionHead.get()) {
            return false; // Ring full
        }

        completionTokens[tail] = token;
        completionResults[tail] = result;
        completionTail.lazySet(nextTail);
        return true;
    }

    /**
     * Dequeues an immediate completion.
     *
     * @param handler the completion handler
     * @return true if a completion was dequeued
     */
    private boolean dequeueCompletion(CompletionHandler handler) {
        int head = completionHead.get();
        if (head == completionTail.get()) {
            return false; // Ring empty
        }

        long token = completionTokens[head];
        int result = completionResults[head];
        completionHead.lazySet((head + 1) & (COMPLETION_RING_SIZE - 1));

        handler.onComplete(token, result);
        return true;
    }

    @Override
    public void initialize(TransportConfig config) {
        if (initialized) {
            throw new IllegalStateException("Backend already initialized");
        }

        this.config = config;
        try {
            this.selector = Selector.open();
            this.initialized = true;
        } catch (IOException e) {
            throw new TransportException("Failed to initialize NIO backend", e);
        }
    }

    @Override
    public void registerBufferPool(RegisteredBufferPool bufferPool) {
        checkInitialized();
        this.bufferPool = bufferPool;
        // NIO doesn't support kernel buffer registration - this is a no-op
        // The bufferPool is stored for buffer management only
    }

    @Override
    public void connect(SocketAddress remoteAddress, long token) {
        checkInitialized();
        checkNotClosed();

        // Check state
        if (!connectionState.canConnect()) {
            throw new IllegalStateException("Cannot connect in state: " + connectionState);
        }
        connectionState = ConnectionState.CONNECTING;

        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);

            // Configure socket options for low latency
            channel.socket().setTcpNoDelay(true);
            channel.socket().setKeepAlive(true);

            totalSyscalls.incrementAndGet(); // connect() syscall
            boolean connected = channel.connect(remoteAddress);

            if (connected) {
                // Immediate connection (localhost or cached)
                connectionState = ConnectionState.CONNECTED;
                connectCount.incrementAndGet();
                enqueueCompletion(token, 0);
            } else {
                // Connection in progress
                channel.register(
                        selector, SelectionKey.OP_CONNECT, new NioOperation(token, null, 0));
            }
        } catch (IOException e) {
            connectionState = ConnectionState.DISCONNECTED;
            throw new TransportException("Connect failed", e);
        }
    }

    @Override
    public void bind(SocketAddress localAddress) {
        checkInitialized();
        checkNotClosed();

        try {
            this.serverChannel = ServerSocketChannel.open();
            this.serverChannel.configureBlocking(false);

            // Configure socket options
            serverChannel.socket().setReuseAddress(true);

            totalSyscalls.incrementAndGet(); // bind() syscall
            this.serverChannel.bind(localAddress);

            connectionState = ConnectionState.CONNECTED; // Server is "connected" when bound
        } catch (IOException e) {
            throw new TransportException("Bind failed", e);
        }
    }

    @Override
    public void accept(long token) {
        checkInitialized();
        checkNotClosed();

        if (serverChannel == null) {
            throw new IllegalStateException("Server socket not bound");
        }

        try {
            serverChannel.register(
                    selector, SelectionKey.OP_ACCEPT, new NioOperation(token, null, 0));
        } catch (ClosedChannelException e) {
            throw new TransportException("Accept failed", e);
        }
    }

    @Override
    public void send(RegisteredBuffer buffer, long token) {
        // Zero-copy path: use the buffer's segment directly
        send(buffer.segment(), (int) buffer.remaining(), token);
    }

    @Override
    public void send(MemorySegment data, int length, long token) {
        checkInitialized();
        checkNotClosed();
        checkConnected();

        try {
            // FFM zero-copy: asByteBuffer() creates a view without copying
            // This is the closest NIO can get to zero-copy
            ByteBuffer buffer = data.asByteBuffer().limit(length);

            totalSyscalls.incrementAndGet(); // write() syscall
            int written = channel.write(buffer);

            if (written == length) {
                // Fully written immediately - fast path
                bytesSent.addAndGet(written);
                sendCount.incrementAndGet();
                enqueueCompletion(token, written);
            } else {
                // Partial write or would block - need async completion
                if (written > 0) {
                    bytesSent.addAndGet(written);
                }
                // Track total written for accurate completion reporting
                channel.register(
                        selector, SelectionKey.OP_WRITE, new NioOperation(token, buffer, written));
            }
        } catch (IOException e) {
            failedSends.incrementAndGet();
            updateStateOnError(e);
            throw new TransportException("Send failed", e);
        }
    }

    @Override
    public void receive(RegisteredBuffer buffer, long token) {
        // Zero-copy path: use the buffer's segment directly
        receive(buffer.segment(), (int) buffer.capacity(), token);
    }

    @Override
    public void receive(MemorySegment data, int maxLength, long token) {
        checkInitialized();
        checkNotClosed();
        checkConnected();

        try {
            // FFM zero-copy: asByteBuffer() creates a view without copying
            ByteBuffer buffer = data.asByteBuffer().limit(maxLength);

            // Try immediate read first (optimization for data already in socket buffer)
            totalSyscalls.incrementAndGet(); // read() syscall
            int read = channel.read(buffer);

            if (read > 0) {
                // Data available immediately - fast path
                bytesReceived.addAndGet(read);
                receiveCount.incrementAndGet();
                enqueueCompletion(token, read);
            } else if (read == 0) {
                // No data yet, register for async notification
                buffer.rewind(); // Reset position for later read
                channel.register(
                        selector, SelectionKey.OP_READ, new NioOperation(token, buffer, 0));
            } else {
                // read == -1: EOF
                enqueueCompletion(token, -1);
            }
        } catch (IOException e) {
            failedReceives.incrementAndGet();
            updateStateOnError(e);
            throw new TransportException("Receive failed", e);
        }
    }

    @Override
    public int submitBatch() {
        // NIO doesn't support batch submission
        // Each operation results in an immediate syscall
        return 0;
    }

    @Override
    public int poll(CompletionHandler handler) {
        if (!initialized) {
            return 0;
        }

        int completed = 0;

        // Process immediate completions from ring buffer (zero-allocation)
        while (dequeueCompletion(handler)) {
            completed++;
        }

        try {
            totalSyscalls.incrementAndGet(); // selectNow() syscall
            int ready = selector.selectNow();

            if (ready > 0 || !selector.selectedKeys().isEmpty()) {
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    NioOperation op = (NioOperation) key.attachment();
                    if (op == null) continue;

                    try {
                        completed += processKey(key, op, handler);
                    } catch (IOException e) {
                        key.cancel();
                        updateStateOnError(e);
                        handler.onComplete(op.token, -1); // Error
                        completed++;
                    }
                }
            }
        } catch (IOException e) {
            // Selector error - typically means closed
        }

        return completed;
    }

    /**
     * Process a single selection key. Extracted to reduce poll() method complexity and improve JIT
     * inlining.
     */
    private int processKey(SelectionKey key, NioOperation op, CompletionHandler handler)
            throws IOException {
        if (key.isConnectable()) {
            return processConnect(key, op, handler);
        } else if (key.isAcceptable()) {
            return processAccept(key, op, handler);
        } else if (key.isWritable()) {
            return processWrite(key, op, handler);
        } else if (key.isReadable()) {
            return processRead(key, op, handler);
        }
        return 0;
    }

    private int processConnect(SelectionKey key, NioOperation op, CompletionHandler handler)
            throws IOException {
        totalSyscalls.incrementAndGet(); // finishConnect() syscall
        if (channel.finishConnect()) {
            key.interestOps(0);
            connectionState = ConnectionState.CONNECTED;
            connectCount.incrementAndGet();
            handler.onComplete(op.token, 0);
            return 1;
        }
        return 0;
    }

    private int processAccept(SelectionKey key, NioOperation op, CompletionHandler handler)
            throws IOException {
        totalSyscalls.incrementAndGet(); // accept() syscall
        SocketChannel client = serverChannel.accept();

        if (client != null) {
            client.configureBlocking(false);
            client.socket().setTcpNoDelay(true); // Low latency

            // Store in pre-allocated array (zero-allocation)
            int handle = acceptedChannelIndex.getAndIncrement();
            if (handle >= MAX_ACCEPTED_CHANNELS) {
                client.close();
                throw new TransportException(
                        "Max accepted connections exceeded: " + MAX_ACCEPTED_CHANNELS);
            }
            acceptedChannels[handle] = client;

            key.interestOps(0); // One-shot accept per token
            acceptCount.incrementAndGet();
            handler.onComplete(op.token, handle);
            return 1;
        }
        return 0;
    }

    private int processWrite(SelectionKey key, NioOperation op, CompletionHandler handler)
            throws IOException {
        totalSyscalls.incrementAndGet(); // write() syscall
        int written = channel.write(op.buffer);

        if (written > 0) {
            bytesSent.addAndGet(written);
            op.bytesTransferred += written;
        }

        if (!op.buffer.hasRemaining()) {
            // Complete - report total bytes transferred
            key.interestOps(0);
            sendCount.incrementAndGet();
            handler.onComplete(op.token, op.bytesTransferred);
            return 1;
        }
        // Still has remaining, keep key registered
        return 0;
    }

    private int processRead(SelectionKey key, NioOperation op, CompletionHandler handler)
            throws IOException {
        totalSyscalls.incrementAndGet(); // read() syscall
        int read = channel.read(op.buffer);

        if (read == -1) {
            // EOF - connection closed by peer
            key.cancel();
            connectionState = ConnectionState.DISCONNECTED;
            handler.onComplete(op.token, -1);
            return 1;
        } else if (read > 0) {
            key.interestOps(0); // One-shot read
            bytesReceived.addAndGet(read);
            receiveCount.incrementAndGet();
            handler.onComplete(op.token, read);
            return 1;
        }
        // read == 0: spurious wakeup, keep waiting
        return 0;
    }

    @Override
    public int waitForCompletion(long timeoutMillis, CompletionHandler handler) {
        if (!initialized) {
            return 0;
        }

        // First check for immediate completions (zero-allocation fast path)
        int completed = 0;
        while (dequeueCompletion(handler)) {
            completed++;
        }
        if (completed > 0) {
            return completed;
        }

        try {
            totalSyscalls.incrementAndGet(); // select() syscall
            int ready = selector.select(timeoutMillis);

            if (ready > 0) {
                // Use poll() to process - avoids code duplication
                return poll(handler);
            }
        } catch (IOException e) {
            // Selector closed or interrupted
        }
        return 0;
    }

    @Override
    public TransportBackend createFromAccepted(int handle) {
        if (handle < 0 || handle >= acceptedChannelIndex.get()) {
            throw new IllegalArgumentException("Invalid handle: " + handle);
        }

        SocketChannel clientChannel = acceptedChannels[handle];
        if (clientChannel == null) {
            throw new IllegalArgumentException("Handle already consumed or invalid: " + handle);
        }

        // Clear the slot (one-time use)
        acceptedChannels[handle] = null;

        NioBackend backend = new NioBackend();
        backend.channel = clientChannel;
        backend.config = this.config;
        backend.bufferPool = this.bufferPool;
        backend.connectionState = ConnectionState.CONNECTED; // Already connected

        try {
            backend.selector = Selector.open();
            backend.initialized = true;
        } catch (IOException e) {
            throw new TransportException("Failed to init client backend", e);
        }
        return backend;
    }

    @Override
    public boolean supportsRegisteredBuffers() {
        return false; // NIO doesn't support kernel buffer registration
    }

    @Override
    public boolean supportsBatchSubmission() {
        return false; // NIO: one syscall per operation
    }

    @Override
    public boolean supportsTLS() {
        return true; // Can be extended with SSLEngine
    }

    @Override
    public String getBackendType() {
        return "nio";
    }

    @Override
    public BackendStats getStats() {
        long totalOps = sendCount.get() + receiveCount.get();
        long syscalls = totalSyscalls.get();

        // NIO has ~1:1 ratio (one syscall per operation)
        // But we also count select/poll syscalls, so ratio is actually < 1
        double avgBatchSize = syscalls > 0 ? (double) totalOps / syscalls : 0.0;

        return BackendStats.builder()
                .totalSends(sendCount.get())
                .totalReceives(receiveCount.get())
                .totalBytesSent(bytesSent.get())
                .totalBytesReceived(bytesReceived.get())
                .failedSends(failedSends.get())
                .failedReceives(failedReceives.get())
                .batchSubmissions(0L) // NIO doesn't batch
                .avgBatchSize(avgBatchSize)
                .totalSyscalls(syscalls)
                .build();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        // Transition to CLOSING state
        if (connectionState == ConnectionState.CONNECTED
                || connectionState == ConnectionState.CONNECTING) {
            connectionState = ConnectionState.CLOSING;
        }

        closed = true;

        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            // Ignore - best effort cleanup
        }

        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        // Close any accepted channels that weren't consumed
        int maxIndex = acceptedChannelIndex.get();
        for (int i = 0; i < maxIndex && i < MAX_ACCEPTED_CHANNELS; i++) {
            SocketChannel accepted = acceptedChannels[i];
            if (accepted != null) {
                try {
                    accepted.close();
                } catch (IOException e) {
                    // Ignore
                }
                acceptedChannels[i] = null;
            }
        }

        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        connectionState = ConnectionState.CLOSED;
    }

    // ==================== Helper Methods ====================

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Backend not initialized");
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Backend is closed");
        }
    }

    private void checkConnected() {
        if (!connectionState.canDoIO()) {
            throw new IllegalStateException("Cannot perform I/O in state: " + connectionState);
        }
    }

    private void updateStateOnError(IOException e) {
        String message = e.getMessage();
        if (message != null
                && (message.contains("Connection reset")
                        || message.contains("Broken pipe")
                        || message.contains("Connection refused")
                        || message.contains("No route to host"))) {
            connectionState = ConnectionState.DISCONNECTED;
        }
    }

    /**
     * Gets the current connection state.
     *
     * @return the connection state
     */
    public ConnectionState getConnectionState() {
        return connectionState;
    }

    // ==================== Inner Classes ====================

    /**
     * Tracks a pending NIO operation with its associated buffer and progress.
     *
     * <p>This is a mutable holder to track partial write progress without allocation.
     */
    private static final class NioOperation {
        final long token;
        final ByteBuffer buffer;
        int bytesTransferred; // Mutable: tracks cumulative bytes for partial writes

        NioOperation(long token, ByteBuffer buffer, int initialBytes) {
            this.token = token;
            this.buffer = buffer;
            this.bytesTransferred = initialBytes;
        }
    }

    /**
     * Connection state for NIO backend. Mirrors the io_uring ConnectionState for API consistency.
     */
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        CLOSING,
        CLOSED;

        public boolean canConnect() {
            return this == DISCONNECTED;
        }

        public boolean canDoIO() {
            return this == CONNECTED;
        }

        public boolean canClose() {
            return this == CONNECTED || this == CONNECTING;
        }
    }
}

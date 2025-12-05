package express.mvp.myra.transport;

import java.lang.foreign.MemorySegment;
import java.net.SocketAddress;

/**
 * Backend abstraction for pluggable I/O implementations.
 *
 * <p>This interface defines the low-level I/O operations that can be implemented by different
 * backends (io_uring, NIO, etc.). It provides a consistent API for the transport layer while
 * allowing backend-specific optimizations.
 *
 * <h2>Supported Backends</h2>
 *
 * <table border="1">
 *   <caption>Available Backend Implementations</caption>
 *   <tr><th>Backend</th><th>Platform</th><th>Features</th><th>Performance</th></tr>
 *   <tr>
 *     <td><b>io_uring</b></td>
 *     <td>Linux 5.1+</td>
 *     <td>Registered buffers, batch submission, SQPOLL</td>
 *     <td>Highest (1.7x NIO)</td>
 *   </tr>
 *   <tr>
 *     <td><b>NIO</b></td>
 *     <td>All platforms</td>
 *     <td>Standard Java NIO, selector-based</td>
 *     <td>Baseline</td>
 *   </tr>
 *   <tr>
 *     <td><b>XDP</b></td>
 *     <td>Linux 4.8+ (future)</td>
 *     <td>Kernel bypass via eBPF</td>
 *     <td>Ultra-low latency</td>
 *   </tr>
 *   <tr>
 *     <td><b>DPDK</b></td>
 *     <td>Linux (future)</td>
 *     <td>Full kernel bypass, PMD drivers</td>
 *     <td>Maximum throughput</td>
 *   </tr>
 * </table>
 *
 * <h2>Registered Buffers</h2>
 *
 * <p>Backends that support registered buffers (io_uring, DPDK) implement {@link
 * #supportsRegisteredBuffers()} returning {@code true}. Registration pre-validates memory regions
 * with the kernel, eliminating per-operation overhead:
 *
 * <ul>
 *   <li><b>io_uring:</b> Uses {@code io_uring_register_buffers()} syscall
 *   <li><b>DPDK:</b> Uses IOVA-contiguous memory pools
 *   <li><b>NIO:</b> No registration (falls back to copy)
 * </ul>
 *
 * <h2>Batch Submission</h2>
 *
 * <p>High-performance backends implement batch submission via {@link #submitBatch()}:
 *
 * <ul>
 *   <li><b>Syscall reduction:</b> One syscall submits multiple operations (100x reduction)
 *   <li><b>Ring buffer efficiency:</b> Operations queue in user space, submitted atomically
 *   <li><b>Adaptive batching:</b> Collect operations then submit periodically or when full
 * </ul>
 *
 * <h2>Operation Flow</h2>
 *
 * <pre>
 * 1. Queue operations: connect(), send(), receive()
 *    └─&gt; Added to submission queue (no syscall)
 *
 * 2. Submit batch: submitBatch()
 *    └─&gt; Single syscall submits all queued ops
 *
 * 3. Wait for completions: poll() or waitForCompletion()
 *    └─&gt; CompletionHandler called for each complete op
 * </pre>
 *
 * <h2>Token-Based Correlation</h2>
 *
 * <p>Each operation accepts a {@code token} parameter that is preserved through completion. This
 * allows applications to track operations without additional allocation or data structures.
 *
 * @see express.mvp.myra.transport.iouring.IoUringBackend
 * @see express.mvp.myra.transport.nio.NioBackend
 * @see Transport
 */
public interface TransportBackend extends AutoCloseable {

    /**
     * Initializes the backend with the given configuration.
     *
     * <p>This is called once during transport creation. Implementations should allocate resources,
     * initialize the I/O subsystem, and prepare for operations.
     *
     * @param config the transport configuration
     * @throws TransportException if initialization fails
     */
    void initialize(TransportConfig config);

    /**
     * Registers a buffer pool with this backend for zero-copy I/O.
     *
     * <p>For io_uring, this calls {@code io_uring_register_buffers()} to pre-register memory
     * regions with the kernel. For other backends, this may be a no-op.
     *
     * @param bufferPool the buffer pool to register
     * @throws UnsupportedOperationException if backend doesn't support registered buffers
     */
    void registerBufferPool(RegisteredBufferPool bufferPool);

    /**
     * Connects to a remote endpoint asynchronously.
     *
     * @param remoteAddress the remote address to connect to
     * @param token the token identifying the operation
     */
    void connect(SocketAddress remoteAddress, long token);

    /**
     * Binds to a local address for accepting connections (server mode).
     *
     * @param localAddress the local address to bind to
     */
    void bind(SocketAddress localAddress);

    /**
     * Accepts an incoming connection (server mode).
     *
     * @param token the token identifying the operation
     */
    void accept(long token);

    /**
     * Sends data using a registered buffer (zero-copy).
     *
     * <p>For io_uring, this uses {@code io_uring_prep_send_fixed()} with the buffer index. For
     * other backends, this may fall back to a regular send.
     *
     * @param buffer the registered buffer containing data to send
     * @param token the token identifying the operation
     */
    void send(RegisteredBuffer buffer, long token);

    /**
     * Sends data using a raw memory segment.
     *
     * <p>This is a fallback for non-registered buffers. Less efficient than {@link
     * #send(RegisteredBuffer, long)} but more flexible.
     *
     * @param data the memory segment containing data to send
     * @param length the number of bytes to send
     * @param token the token identifying the operation
     */
    void send(MemorySegment data, int length, long token);

    /**
     * Receives data into a registered buffer (zero-copy).
     *
     * <p>For io_uring, this uses {@code io_uring_prep_recv_fixed()} with the buffer index.
     *
     * @param buffer the registered buffer to receive into
     * @param token the token identifying the operation
     */
    void receive(RegisteredBuffer buffer, long token);

    /**
     * Receives data into a raw memory segment.
     *
     * @param data the memory segment to receive into
     * @param maxLength the maximum number of bytes to receive
     * @param token the token identifying the operation
     */
    void receive(MemorySegment data, int maxLength, long token);

    /**
     * Submits all queued operations in a batch.
     *
     * <p>For io_uring, this calls {@code io_uring_submit()} to submit all queued operations with a
     * single syscall. This is the key to achieving 100x syscall reduction.
     *
     * <p>For NIO, this is typically a no-op since NIO doesn't support batching.
     *
     * @return the number of operations submitted
     */
    int submitBatch();

    /**
     * Polls for completions without blocking.
     *
     * <p>For io_uring, this calls {@code io_uring_peek_cqe()} to check for completed operations.
     * For NIO, this may use a Selector with 0 timeout.
     *
     * @param handler the handler to call for each completion
     * @return the number of operations completed
     */
    int poll(CompletionHandler handler);

    /**
     * Waits for at least one operation to complete.
     *
     * <p>This blocks until at least one queued operation completes.
     *
     * @param timeoutMillis the maximum time to wait in milliseconds (0 = forever)
     * @param handler the handler to call for each completion
     * @return the number of operations completed
     */
    int waitForCompletion(long timeoutMillis, CompletionHandler handler);

    /**
     * Returns whether this backend supports registered buffers.
     *
     * @return true if registered buffers are supported
     */
    boolean supportsRegisteredBuffers();

    /**
     * Returns whether this backend supports batch submission.
     *
     * @return true if batch submission is supported
     */
    boolean supportsBatchSubmission();

    /**
     * Returns whether this backend supports TLS/SSL.
     *
     * @return true if TLS is supported
     */
    boolean supportsTLS();

    /**
     * Returns the backend type identifier.
     *
     * @return the backend type (e.g., "io_uring", "nio", "xdp")
     */
    String getBackendType();

    /**
     * Returns statistics about backend operations.
     *
     * @return backend statistics including operation counts, errors, etc.
     */
    BackendStats getStats();

    /** Closes the backend and releases all resources. */
    @Override
    void close();

    /**
     * Creates a new backend instance from an accepted connection handle.
     *
     * <p>For io_uring, the handle is the file descriptor. For NIO, the handle is an index or
     * reference to the accepted channel.
     *
     * @param handle the handle returned by the accept completion
     * @return a new TransportBackend for the accepted connection
     */
    TransportBackend createFromAccepted(int handle);
}

package express.mvp.myra.transport;

import java.lang.foreign.MemorySegment;
import java.net.SocketAddress;

/**
 * Core transport abstraction for bidirectional byte streaming.
 *
 * <p>This interface provides a high-level, codec-agnostic API for network communication with
 * support for zero-copy I/O using registered buffers. It abstracts over different I/O backends
 * (io_uring, NIO) while exposing maximum performance.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────┐
 * │                      Transport                          │
 * ├─────────────────────────────────────────────────────────┤
 * │  • High-level API (connect, send, receive)              │
 * │  • Buffer pool management                               │
 * │  • Connection lifecycle                                 │
 * │  • Health monitoring                                    │
 * └─────────────────────────────────────────────────────────┘
 *                           │
 *                           ▼
 * ┌─────────────────────────────────────────────────────────┐
 * │                   TransportBackend                      │
 * ├─────────────────────────────────────────────────────────┤
 * │  io_uring (Linux)  │  NIO (fallback)  │  XDP (future)   │
 * └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Asynchronous Operation Model</h2>
 *
 * <p>All I/O operations are asynchronous and return a token for tracking:
 *
 * <ol>
 *   <li><b>Submit:</b> Call {@link #send(MemorySegment)} or {@link #connect(SocketAddress)}
 *   <li><b>Track:</b> Use the returned token to correlate with completions
 *   <li><b>Complete:</b> Receive callbacks via {@link TransportHandler}
 * </ol>
 *
 * <h2>Zero-Copy I/O</h2>
 *
 * <p>For maximum performance, use registered buffers:
 *
 * <pre>{@code
 * RegisteredBuffer buffer = transport.acquireBuffer();
 * try {
 *     // Write data directly to off-heap memory
 *     buffer.segment().copyFrom(data);
 *     buffer.position(data.byteSize());
 *     buffer.flip();
 *
 *     // Zero-copy send
 *     transport.send(buffer.segment());
 * } finally {
 *     buffer.close(); // Return to pool
 * }
 * }</pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * TransportConfig config = TransportConfig.builder()
 *     .bufferCount(256)
 *     .bufferSize(65536)
 *     .build();
 *
 * try (Transport transport = TransportFactory.create(config)) {
 *     transport.start(new TransportHandler() {
 *         @Override
 *         public void onConnect(long token, int result) {
 *             if (result >= 0) {
 *                 System.out.println("Connected!");
 *             }
 *         }
 *
 *         @Override
 *         public void onReceive(RegisteredBuffer buffer, int bytesReceived) {
 *             // Process received data
 *         }
 *     });
 *
 *     transport.connect(new InetSocketAddress("localhost", 8080));
 *
 *     // Event loop
 *     while (transport.isConnected()) {
 *         // Process completions...
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be thread-safe for concurrent I/O operations from multiple threads.
 * Buffer acquisition and release are thread-safe.
 *
 * @see TransportBackend
 * @see TransportFactory
 * @see TransportHandler
 * @see RegisteredBuffer
 */
public interface Transport extends AutoCloseable {

    /**
     * Initiates an asynchronous connection to a remote endpoint.
     *
     * <p>The connection attempt runs asynchronously. When complete, the {@link
     * TransportHandler#onConnected(long)} callback is invoked with the result (0 for success,
     * negative errno for failure).
     *
     * <h3>Token Usage</h3>
     *
     * <p>The returned token is passed to the completion handler and can be used to correlate this
     * operation with application-level state.
     *
     * @param remoteAddress the remote address to connect to (e.g., {@code InetSocketAddress})
     * @return a token identifying this operation for correlation in completion callbacks
     * @throws TransportException if the operation cannot be submitted
     * @see TransportHandler#onConnected(long)
     */
    long connect(SocketAddress remoteAddress);

    /**
     * Sends data asynchronously.
     *
     * <p>The send operation is queued and executed asynchronously. For best performance, use
     * buffers obtained from {@link #acquireBuffer()} which enables zero-copy I/O.
     *
     * <p><b>Memory Ownership:</b> The caller retains ownership of the data segment. The data is
     * copied (or pinned for zero-copy) before this method returns, so the segment can be reused
     * immediately.
     *
     * @param data the memory segment containing data to send
     * @return a token identifying this operation for correlation in completion callbacks
     * @throws TransportException if the operation cannot be submitted
     * @throws IllegalStateException if not connected
     * @see TransportHandler#onSendComplete(long)
     */
    long send(MemorySegment data);

    /**
     * Starts the transport with the specified handler for receiving callbacks.
     *
     * <p>This method must be called before any I/O operations. The handler receives all completion
     * callbacks (connect, send, receive, errors).
     *
     * <p><b>Lifecycle:</b> After calling start, the transport is ready for I/O. Call {@link
     * #close()} to shut down cleanly.
     *
     * @param handler the callback handler for I/O completion events
     * @throws IllegalStateException if already started
     * @throws TransportException if initialization fails
     */
    void start(TransportHandler handler);

    /**
     * Acquires a registered buffer for zero-copy I/O.
     *
     * <p>Registered buffers are pre-validated by the kernel, eliminating per-operation address
     * validation overhead. This provides approximately 1.7x throughput improvement with io_uring.
     *
     * <p><b>Blocking Behavior:</b> This method blocks if no buffers are available until one is
     * released.
     *
     * <p><b>Resource Management:</b> Always release buffers via {@link RegisteredBuffer#close()}
     * when done, typically in a try-with-resources block.
     *
     * @return a registered buffer from the pool, ready for use
     * @throws TransportException if interrupted while waiting
     * @see RegisteredBuffer
     * @see RegisteredBufferPool
     */
    RegisteredBuffer acquireBuffer();

    /**
     * Returns the number of buffers currently available in the pool.
     *
     * <p>This can be used to implement backpressure or monitoring. Note that the value may change
     * immediately after the call returns.
     *
     * @return the number of available buffers
     */
    int availableBufferSpace();

    /**
     * Returns the connection pool managing connections for this transport.
     *
     * <p>The connection pool tracks active connections and provides metrics about connection state.
     *
     * @return the connection pool (never null)
     */
    ConnectionPool getConnectionPool();

    /**
     * Returns health metrics for this transport.
     *
     * <p>Health information includes buffer utilization, error rates, and throughput metrics.
     *
     * @return transport health information (never null)
     */
    TransportHealth getHealth();

    /**
     * Checks if the transport has an active connection.
     *
     * @return {@code true} if connected to a remote endpoint
     */
    boolean isConnected();

    /**
     * Returns the local socket address.
     *
     * @return the local address, or {@code null} if not bound
     */
    SocketAddress getLocalAddress();

    /**
     * Returns the remote socket address.
     *
     * @return the remote address, or {@code null} if not connected
     */
    SocketAddress getRemoteAddress();

    /**
     * Closes the transport and releases all resources.
     *
     * <p>This method:
     *
     * <ul>
     *   <li>Closes all active connections gracefully
     *   <li>Releases the buffer pool
     *   <li>Shuts down the I/O backend
     *   <li>Frees all off-heap memory
     * </ul>
     *
     * <p>After closing, the transport cannot be reused. Create a new instance if needed.
     */
    @Override
    void close();
}

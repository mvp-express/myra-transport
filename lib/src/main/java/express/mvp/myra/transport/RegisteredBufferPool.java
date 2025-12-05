package express.mvp.myra.transport;

/**
 * Thread-safe pool for managing pre-registered I/O buffers.
 *
 * <p>This pool maintains a fixed set of off-heap memory buffers that are pre-registered with the
 * I/O backend (e.g., io_uring) for zero-copy operations. Pre-registration eliminates per-operation
 * kernel address validation, providing significant performance improvements.
 *
 * <h2>Performance Benefits</h2>
 *
 * <ul>
 *   <li><b>1.7x throughput improvement</b> with io_uring registered buffers
 *   <li><b>Zero heap allocations</b> during I/O operations (GC-free)
 *   <li><b>Eliminated validation overhead</b> per I/O operation
 *   <li><b>Contiguous memory</b> for improved cache and TLB locality
 * </ul>
 *
 * <h2>Pool Sizing Guidelines</h2>
 *
 * <ul>
 *   <li><b>Buffer count:</b> Should match expected concurrent I/O operations. Typical values:
 *       64-512 for clients, 256-4096 for servers.
 *   <li><b>Buffer size:</b> Should match typical message size plus overhead. Typical values:
 *       4KB-64KB depending on protocol.
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Create pool (typically once at startup)
 * RegisteredBufferPool pool = new RegisteredBufferPoolImpl(256, 65536);
 * backend.registerBufferPool(pool);
 *
 * // Acquire buffer for I/O (hot path)
 * RegisteredBuffer buffer = pool.acquire(); // Blocks if none available
 * try {
 *     // Write data to buffer
 *     buffer.segment().copyFrom(data);
 *     buffer.limit(data.byteSize());
 *
 *     // Send using registered buffer (zero-copy)
 *     backend.send(buffer, token);
 * } finally {
 *     // Release is typically done in completion handler
 *     // buffer.close();
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. The pool uses lock-free data structures internally for
 * acquire/release operations to minimize contention.
 *
 * @see RegisteredBuffer
 * @see RegisteredBufferPoolImpl
 * @see TransportBackend#registerBufferPool(RegisteredBufferPool)
 */
public interface RegisteredBufferPool extends AutoCloseable {

    /**
     * Acquires a buffer from the pool, blocking if necessary.
     *
     * <p>If no buffers are currently available, this method blocks until one is released by another
     * thread. The returned buffer has been cleared (position=0, limit=capacity).
     *
     * @return a registered buffer ready for use
     * @throws IllegalStateException if the pool is closed
     * @throws express.mvp.myra.transport.TransportException if interrupted while waiting
     */
    RegisteredBuffer acquire();

    /**
     * Attempts to acquire a buffer without blocking.
     *
     * <p>Returns immediately with a buffer if one is available, or {@code null} if all buffers are
     * in use. This is useful for non-blocking I/O patterns.
     *
     * @return a registered buffer, or {@code null} if none available
     * @throws IllegalStateException if the pool is closed
     */
    RegisteredBuffer tryAcquire();

    /**
     * Releases a buffer back to the pool for reuse.
     *
     * <p>The buffer is automatically cleared (position reset, limit set to capacity) before being
     * made available for the next acquire. This method is also called automatically when {@link
     * RegisteredBuffer#close()} is invoked.
     *
     * <p>This method is idempotent; releasing an already-released buffer has no effect.
     *
     * @param buffer the buffer to release; must have been acquired from this pool
     * @throws IllegalArgumentException if buffer is not from this pool
     */
    void release(RegisteredBuffer buffer);

    /**
     * Returns the total number of buffers in the pool.
     *
     * <p>This is the fixed capacity set at pool creation time.
     *
     * @return the total buffer count
     */
    int capacity();

    /**
     * Returns the number of buffers currently available for acquisition.
     *
     * <p>Note: This value may change immediately after the call returns due to concurrent
     * acquire/release operations.
     *
     * @return the current available buffer count
     */
    int available();

    /**
     * Returns the number of buffers currently in use.
     *
     * <p>This equals {@code capacity() - available()}. Note: This value may change immediately
     * after the call returns.
     *
     * @return the current in-use buffer count
     */
    int inUse();

    /**
     * Closes the pool and releases all underlying resources.
     *
     * <p>After closing:
     *
     * <ul>
     *   <li>All buffers become invalid (accessing them is undefined behavior)
     *   <li>{@link #acquire()} and {@link #tryAcquire()} will throw
     *   <li>The underlying memory is freed
     * </ul>
     *
     * <p>This method blocks until all buffers can be reclaimed. Ensure all I/O operations using
     * buffers from this pool have completed before closing.
     */
    @Override
    void close();
}

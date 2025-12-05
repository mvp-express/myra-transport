package express.mvp.myra.transport;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A pre-registered off-heap buffer for zero-copy I/O operations.
 *
 * <p>Registered buffers are memory regions that have been pre-validated and pinned by the kernel,
 * eliminating per-operation address validation overhead. On Linux io_uring, this provides
 * approximately 1.7x throughput improvement.
 *
 * <h2>Buffer Lifecycle</h2>
 *
 * <ol>
 *   <li><b>Acquire:</b> Obtain a buffer from {@link RegisteredBufferPool#acquire()}
 *   <li><b>Use:</b> Read/write data using {@link #segment()}
 *   <li><b>Release:</b> Return to pool via {@link #close()} (auto-released)
 * </ol>
 *
 * <h2>Position and Limit</h2>
 *
 * <p>Similar to {@link java.nio.ByteBuffer}, this buffer tracks position and limit:
 *
 * <ul>
 *   <li><b>Position:</b> Current read/write offset in the buffer
 *   <li><b>Limit:</b> Upper bound for read/write operations
 *   <li><b>Capacity:</b> Total buffer size (immutable)
 *   <li><b>Remaining:</b> {@code limit - position} (bytes available)
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * try (RegisteredBuffer buffer = pool.acquire()) {
 *     // Write data
 *     MemorySegment seg = buffer.segment();
 *     seg.setAtIndex(ValueLayout.JAVA_BYTE, 0, (byte) 'H');
 *     buffer.position(5);
 *     buffer.flip(); // Prepare for reading/sending
 *
 *     // Send
 *     backend.send(buffer, token);
 * } // Auto-released back to pool
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Individual buffer instances are not thread-safe. Do not share a buffer across threads while it
 * is in use. The pool itself is thread-safe for acquire/release operations.
 *
 * @see RegisteredBufferPool
 * @see express.mvp.myra.transport.iouring.LibUring#registerBuffers
 */
public interface RegisteredBuffer extends AutoCloseable {

    /**
     * Returns the underlying memory segment for direct data access.
     *
     * <p>The segment provides zero-copy access to the buffer's off-heap memory. Use {@link
     * MemorySegment#get} and {@link MemorySegment#set} methods for type-safe memory access.
     *
     * @return the memory segment backing this buffer
     */
    MemorySegment segment();

    /**
     * Returns the registration index of this buffer.
     *
     * <p>For io_uring backends, this is the buffer index used with {@code IORING_OP_READ_FIXED} and
     * {@code IORING_OP_WRITE_FIXED} operations.
     *
     * @return the buffer's index in the registered buffer array (0-based)
     */
    int index();

    /**
     * Returns the total capacity of this buffer in bytes.
     *
     * <p>This is the maximum amount of data the buffer can hold, set at pool creation time and
     * immutable.
     *
     * @return the buffer capacity in bytes
     */
    long capacity();

    /**
     * Returns the current position in the buffer.
     *
     * <p>Position marks the next byte to be read or written.
     *
     * @return the current position (0 to limit)
     */
    long position();

    /**
     * Sets the current position in the buffer.
     *
     * @param position the new position (must be 0 to limit)
     * @throws IllegalArgumentException if position is negative or exceeds limit
     */
    void position(long position);

    /**
     * Returns the current limit of the buffer.
     *
     * <p>Limit marks the end of valid data for reading, or the maximum write position.
     *
     * @return the current limit (0 to capacity)
     */
    long limit();

    /**
     * Sets the limit of the buffer.
     *
     * <p>If position exceeds the new limit, position is set to the limit.
     *
     * @param limit the new limit (must be 0 to capacity)
     * @throws IllegalArgumentException if limit is negative or exceeds capacity
     */
    void limit(long limit);

    /**
     * Clears the buffer for writing.
     *
     * <p>Sets position to 0 and limit to capacity. Does not actually clear the data; just resets
     * the markers.
     *
     * @return this buffer for method chaining
     */
    RegisteredBuffer clear();

    /**
     * Flips the buffer from writing to reading mode.
     *
     * <p>Sets limit to current position, then sets position to 0. Call after writing data and
     * before reading or sending.
     *
     * @return this buffer for method chaining
     */
    RegisteredBuffer flip();

    /**
     * Returns the number of bytes between position and limit.
     *
     * @return {@code limit - position}
     */
    long remaining();

    /**
     * Checks if there are bytes remaining between position and limit.
     *
     * @return {@code true} if {@code remaining() > 0}
     */
    boolean hasRemaining();

    /**
     * Checks if this buffer is currently acquired from the pool.
     *
     * @return {@code true} if the buffer is in use, {@code false} if available
     */
    boolean isInUse();

    /**
     * Sets a user-defined token for tracking this buffer through I/O operations.
     *
     * <p>This allows passing context through the I/O pipeline without allocation. The token is
     * preserved until overwritten or the buffer is released.
     *
     * @param token the user-defined tracking token
     */
    void setToken(long token);

    /**
     * Gets the user-defined token previously set via {@link #setToken(long)}.
     *
     * @return the tracking token, or 0 if not set
     */
    long getToken();

    /**
     * Returns the arena managing this buffer's memory lifecycle.
     *
     * @return the memory arena
     */
    Arena arena();

    /**
     * Releases this buffer back to its pool.
     *
     * <p>After calling close, the buffer must not be used. The buffer's contents may be overwritten
     * by subsequent acquire operations.
     *
     * <p>This method is idempotent; calling it multiple times has no effect.
     */
    @Override
    void close();
}

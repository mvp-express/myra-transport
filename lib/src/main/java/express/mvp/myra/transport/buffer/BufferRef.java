package express.mvp.myra.transport.buffer;

import java.lang.foreign.MemorySegment;

/**
 * A reference-counted handle to a shared off-heap memory segment.
 *
 * <p>This is the fundamental "Atom" of the transport's buffer system. It wraps a {@link
 * MemorySegment} with explicit ownership semantics via reference counting, designed for efficient
 * pooling and reuse.
 *
 * <h2>Reference Counting</h2>
 *
 * <p>BufferRef uses explicit reference counting for memory management:
 *
 * <ul>
 *   <li><b>Acquire:</b> When obtained from pool, refCount starts at 1
 *   <li><b>Retain:</b> Call {@link #retain()} to increment count when sharing
 *   <li><b>Release:</b> Call {@link #release()} when done; auto-returns to pool at 0
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * BufferRef buf = pool.acquire();
 * try {
 *     // Write data to segment
 *     buf.segment().setAtIndex(ValueLayout.JAVA_BYTE, 0, data);
 *     buf.length(dataLen);
 *
 *     // If sharing with another thread:
 *     buf.retain();
 *     executor.submit(() -> {
 *         try {
 *             process(buf);
 *         } finally {
 *             buf.release();
 *         }
 *     });
 * } finally {
 *     buf.release(); // Returns to pool when refCount hits 0
 * }
 * }</pre>
 *
 * <h2>Structure of Arrays (SoA) Pattern</h2>
 *
 * <p>The {@link #poolIndex()} method supports SoA patterns where metadata (timestamps, flags, etc.)
 * is stored in separate primitive arrays indexed by pool index, avoiding object header overhead.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Reference counting operations ({@link #retain()}, {@link #release()}) are thread-safe using
 * lock-free CAS operations. The underlying segment access is not synchronized; callers must ensure
 * proper happens-before relationships.
 *
 * @see BufferRefImpl
 * @see LockFreeBufferPool
 */
public interface BufferRef {

    /**
     * Returns the underlying FFM MemorySegment.
     *
     * <p><b>Warning:</b> Accessing this segment after {@link #release()} has reduced the refCount
     * to 0 is undefined behavior. The segment may be reused by another thread or the underlying
     * memory may be invalidated.
     *
     * @return the memory segment backing this buffer
     */
    MemorySegment segment();

    /**
     * Returns the raw native address of the segment.
     *
     * <p>This is cached to avoid JNI/FFM overhead on hot paths. Useful for direct native library
     * integration.
     *
     * @return the native memory address
     */
    long address();

    /**
     * Returns the unique index of this buffer in the global pool.
     *
     * <p>This enables "Structure of Arrays" (SoA) patterns where metadata (refCounts, timestamps,
     * operation tokens) is stored in separate primitive arrays indexed by this ID, rather than in
     * object fields.
     *
     * @return the buffer's index in its pool (0-based)
     */
    int poolIndex();

    /**
     * Returns the io_uring registration ID if registered.
     *
     * <p>For io_uring backends, this is the buffer index used with {@code IORING_OP_READ_FIXED} and
     * {@code IORING_OP_WRITE_FIXED}.
     *
     * @return the registration ID, or -1 if not registered
     */
    short registrationId();

    /**
     * Returns the current length of valid data in the buffer.
     *
     * <p>This represents the actual payload size (e.g., packet length), not the buffer capacity.
     *
     * @return the valid data length in bytes
     */
    int length();

    /**
     * Sets the length of valid data in the buffer.
     *
     * @param newLength the new data length in bytes
     */
    void length(int newLength);

    /**
     * Increments the reference count.
     *
     * <p>Must be called before sharing the buffer with another thread or holding it asynchronously
     * past the current scope.
     *
     * @throws IllegalStateException if refCount is already 0 (buffer released)
     */
    void retain();

    /**
     * Decrements the reference count.
     *
     * <p>If the count reaches 0, the buffer is automatically returned to its pool for reuse. After
     * the final release, the buffer must not be accessed.
     *
     * @throws IllegalStateException if refCount is already 0 (double-release)
     */
    void release();
}

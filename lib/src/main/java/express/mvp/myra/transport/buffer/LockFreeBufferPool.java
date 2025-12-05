package express.mvp.myra.transport.buffer;

import express.mvp.roray.utils.collections.RingBuffer;
import express.mvp.roray.utils.collections.RingBufferImpl;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Lock-free buffer pool using a ring buffer for index tracking.
 *
 * <p>This pool provides high-performance buffer allocation without locks, using a MPSC ring buffer
 * to track free buffer indices.
 *
 * <h2>Memory Layout</h2>
 *
 * <p>The pool allocates a single contiguous "slab" of memory and slices it into individual buffers.
 * This provides several benefits:
 *
 * <ul>
 *   <li><b>TLB efficiency:</b> Contiguous memory uses fewer TLB entries
 *   <li><b>Cache locality:</b> Sequential buffers are cache-line adjacent
 *   <li><b>Simple registration:</b> Single memory region for io_uring registration
 *   <li><b>Alignment:</b> 64-byte cache-line alignment for all buffers
 * </ul>
 *
 * <h2>Lock-Free Design</h2>
 *
 * <p>The pool uses a lock-free ring buffer for tracking free indices:
 *
 * <ul>
 *   <li><b>Acquire:</b> Atomically poll an index from the ring
 *   <li><b>Release:</b> Atomically offer an index back to the ring
 *   <li><b>No contention:</b> MPSC design optimizes for common patterns
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * LockFreeBufferPool pool = new LockFreeBufferPool(256, 65536);
 *
 * BufferRef buf = pool.acquire();
 * if (buf != null) {
 *     try {
 *         // Use buffer...
 *         buf.segment().setAtIndex(...);
 *         buf.length(dataLen);
 *     } finally {
 *         buf.release(); // Auto-returns to pool
 *     }
 * }
 * }</pre>
 *
 * <h2>Power of 2 Requirement</h2>
 *
 * <p>The pool count must be a power of 2 for efficient ring buffer operations (enables bit-masking
 * instead of modulo for index wrapping).
 *
 * @see BufferRef
 * @see BufferRefImpl
 */
public class LockFreeBufferPool implements AutoCloseable {

    /** Arena managing the lifetime of all buffer memory. */
    private final Arena arena;

    /** Array of all buffer references (indexed by pool index). */
    private final BufferRefImpl[] buffers;

    /** Lock-free ring buffer tracking free buffer indices. */
    private final RingBuffer freeIndices;

    /** Size of each buffer in bytes. */
    private final int bufferSize;

    /**
     * Creates a new lock-free buffer pool.
     *
     * @param count the number of buffers (must be a power of 2)
     * @param bufferSize the size of each buffer in bytes
     * @throws IllegalArgumentException if count is not a power of 2
     */
    public LockFreeBufferPool(int count, int bufferSize) {
        if (Integer.bitCount(count) != 1) {
            throw new IllegalArgumentException("Pool count must be a power of 2");
        }
        this.bufferSize = bufferSize;
        this.arena = Arena.ofShared();
        this.buffers = new BufferRefImpl[count];
        this.freeIndices = new RingBufferImpl(count);

        // Allocate one giant slab for memory locality (TLB friendly)
        // Alignment 64 bytes (cache line) for optimal performance
        MemorySegment slab = arena.allocate((long) count * bufferSize, 64);

        // Slice slab into individual buffers
        for (int i = 0; i < count; i++) {
            MemorySegment slice = slab.asSlice((long) i * bufferSize, bufferSize);
            // 1:1 mapping: poolIndex == registrationId for io_uring
            buffers[i] = new BufferRefImpl(slice, i, (short) i, this::returnToPool);
            freeIndices.offer(i);
        }
    }

    /**
     * Acquires a buffer from the pool.
     *
     * <p>The returned buffer has refCount=1 and length=0. This method is non-blocking and
     * lock-free.
     *
     * @return a BufferRef with refCount=1, or {@code null} if pool is empty
     */
    public BufferRef acquire() {
        int index = freeIndices.poll();
        if (index == -1) {
            return null; // Pool exhausted
        }
        BufferRefImpl buf = buffers[index];
        buf.reset();
        return buf;
    }

    /**
     * Returns a buffer to the pool by index.
     *
     * <p>Called automatically when a BufferRef's refCount reaches 0.
     *
     * @param index the pool index of the buffer to return
     * @throws IllegalStateException if pool is full (indicates double-free)
     */
    private void returnToPool(int index) {
        if (!freeIndices.offer(index)) {
            // This indicates a double-free or logic error
            throw new IllegalStateException("Pool full, cannot return buffer " + index);
        }
    }

    /**
     * Returns the total capacity of the pool.
     *
     * @return the number of buffers in the pool
     */
    public int capacity() {
        return buffers.length;
    }

    /**
     * Returns the number of currently available buffers.
     *
     * @return the available buffer count
     */
    public int available() {
        return freeIndices.size();
    }

    /**
     * Closes the pool and releases all memory.
     *
     * <p>After closing, all buffers become invalid. Ensure no buffers are in use before calling
     * close.
     */
    @Override
    public void close() {
        freeIndices.close();
        arena.close();
    }
}

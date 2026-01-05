package express.mvp.myra.transport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe pool implementation of pre-registered buffers for zero-copy I/O.
 *
 * <p>This implementation manages a fixed set of off-heap memory regions that can be pre-registered
 * with the I/O backend (e.g., io_uring's registered buffers). Registration eliminates per-operation
 * kernel address validation, providing approximately 1.7x throughput improvement.
 *
 * <h2>Memory Layout</h2>
 *
 * <p>The pool allocates a single contiguous memory block, then slices it into individual buffers.
 * This design provides several benefits:
 *
 * <ul>
 *   <li><b>TLB efficiency:</b> Contiguous memory uses fewer TLB entries
 *   <li><b>Huge page support:</b> Large pools automatically use 2MB huge pages
 *   <li><b>Simpler registration:</b> Single memory region for kernel registration
 *   <li><b>Cache locality:</b> Sequential buffers are cache-line adjacent
 * </ul>
 *
 * <h2>Page Alignment</h2>
 *
 * <p>Buffer sizes are aligned to 4KB page boundaries. This ensures:
 *
 * <ul>
 *   <li>Each buffer starts on a page boundary
 *   <li>DMA transfers work correctly for registered buffers
 *   <li>No false sharing between adjacent buffers
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This implementation uses an {@link ArrayBlockingQueue} for buffer management, providing
 * thread-safe acquire/release with blocking semantics. The queue is lock-based but contention is
 * typically low as I/O operations take much longer than queue operations.
 *
 * <h2>Sizing Recommendations</h2>
 *
 * <table border="1">
 *   <caption>Recommended Pool Sizes by Use Case</caption>
 *   <tr><th>Use Case</th><th>Buffer Count</th><th>Buffer Size</th></tr>
 *   <tr><td>Client (low concurrency)</td><td>64-128</td><td>8-16 KB</td></tr>
 *   <tr><td>Server (moderate load)</td><td>256-512</td><td>16-32 KB</td></tr>
 *   <tr><td>High-throughput server</td><td>1024-4096</td><td>32-64 KB</td></tr>
 * </table>
 *
 * @see RegisteredBufferPool
 * @see RegisteredBuffer
 * @see express.mvp.myra.transport.iouring.LibUring#registerBuffers
 */
public final class RegisteredBufferPoolImpl implements RegisteredBufferPool {

    /**
     * Array of all buffers in the pool, indexed by buffer index (0 to numBuffers-1). This array is
     * immutable after construction.
     */
    private final RegisteredBufferImpl[] buffers;

    /**
     * Queue of buffers available for acquisition. Provides blocking semantics for {@link
     * #acquire()} when the pool is exhausted.
     */
    private final BlockingQueue<RegisteredBufferImpl> availableBuffers;

    /** Counter tracking buffers currently in use. Used for monitoring and diagnostics. */
    private final AtomicInteger inUseCount = new AtomicInteger(0);

    /**
     * Shared arena managing the lifetime of all buffer memory. When closed, all buffer segments
     * become invalid.
     */
    private final Arena arena;

    /** Flag indicating whether the pool has been closed. Checked before acquire operations. */
    private volatile boolean closed = false;

    /**
     * Standard page size (4KB) for buffer alignment. All buffer sizes are rounded up to this
     * boundary.
     */
    private static final int PAGE_SIZE = 4096;

    /**
     * Huge page size (2MB) for large pool alignment. Pools larger than this use huge page alignment
     * for improved TLB efficiency.
     */
    private static final int HUGE_PAGE_SIZE = 2 * 1024 * 1024;

    /**
     * Creates a new buffer pool with the specified number of buffers and size.
     *
     * <p>The actual buffer size may be larger than requested due to page alignment. For example,
     * requesting 5000-byte buffers will allocate 8192 bytes per buffer (rounded up to 2 pages).
     *
     * <h3>Memory Calculation</h3>
     *
     * <p>Total memory = {@code numBuffers × alignedBufferSize}, where:
     *
     * <pre>
     * alignedBufferSize = ((bufferSize + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE
     * </pre>
     *
     * @param numBuffers the number of buffers in the pool (must be positive)
     * @param bufferSize the requested size of each buffer in bytes (must be positive)
     * @throws IllegalArgumentException if numBuffers or bufferSize is not positive
     * @throws OutOfMemoryError if unable to allocate the required memory
     */
    public RegisteredBufferPoolImpl(int numBuffers, int bufferSize) {
        this.arena = Arena.ofShared();
        this.buffers = new RegisteredBufferImpl[numBuffers];
        this.availableBuffers = new ArrayBlockingQueue<>(numBuffers);

        // Align buffer size to page boundary to ensure every buffer starts on a page.
        // This is critical for zero-copy efficiency and kernel registration.
        // Formula: round up to next multiple of PAGE_SIZE using bit manipulation.
        int alignedBufferSize = (bufferSize + PAGE_SIZE - 1) & ~(PAGE_SIZE - 1);
        long totalSize = (long) numBuffers * alignedBufferSize;

        // Use huge page alignment (2MB) if the total pool size is large enough.
        // This reduces TLB pressure for large buffer pools.
        long alignment = totalSize >= HUGE_PAGE_SIZE ? HUGE_PAGE_SIZE : PAGE_SIZE;

        // Allocate a single contiguous block for the entire pool.
        // This improves TLB locality, enables huge pages, and simplifies io_uring registration.
        MemorySegment baseSegment = arena.allocate(totalSize, alignment);

        // Slice the contiguous block into individual buffers.
        // Each slice shares the same underlying memory but has its own offset/size.
        for (int i = 0; i < numBuffers; i++) {
            MemorySegment segment =
                    baseSegment.asSlice((long) i * alignedBufferSize, alignedBufferSize);
            RegisteredBufferImpl buffer = new RegisteredBufferImpl(i, segment, this);
            buffers[i] = buffer;
            if (!availableBuffers.offer(buffer)) {
                throw new IllegalStateException("Failed to initialize buffer pool queue");
            }
        }
    }

    @Override
    public RegisteredBuffer acquire() {
        if (closed) {
            throw new IllegalStateException("Buffer pool is closed");
        }

        try {
            // Block until a buffer becomes available.
            // This is the hot path for I/O operations.
            RegisteredBufferImpl buffer = availableBuffers.take();
            buffer.markInUse();
            inUseCount.incrementAndGet();
            return buffer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Interrupted while acquiring buffer", e);
        }
    }

    @Override
    public RegisteredBuffer tryAcquire() {
        if (closed) {
            throw new IllegalStateException("Buffer pool is closed");
        }

        // Non-blocking attempt to acquire a buffer.
        // Returns null immediately if no buffers are available.
        RegisteredBufferImpl buffer = availableBuffers.poll();
        if (buffer != null) {
            buffer.markInUse();
            inUseCount.incrementAndGet();
        }
        return buffer;
    }

    @Override
    public void release(RegisteredBuffer buffer) {
        if (!(buffer instanceof RegisteredBufferImpl)) {
            throw new IllegalArgumentException("Buffer not from this pool");
        }

        RegisteredBufferImpl impl = (RegisteredBufferImpl) buffer;
        if (!impl.isInUse()) {
            return; // Already released - idempotent behavior
        }

        // Reset buffer state and return to pool.
        impl.markAvailable();
        impl.clear(); // Reset position=0, limit=capacity
        inUseCount.decrementAndGet();
        if (!availableBuffers.offer(impl)) {
            throw new IllegalStateException("Buffer pool queue is full");
        }
    }

    @Override
    public int capacity() {
        return buffers.length;
    }

    @Override
    public int available() {
        return availableBuffers.size();
    }

    @Override
    public int inUse() {
        return inUseCount.get();
    }

    /**
     * Returns the array of all buffers for backend registration.
     *
     * <p>This method is called by the I/O backend during initialization to register all buffers
     * with the kernel. For io_uring, this triggers {@link
     * express.mvp.myra.transport.iouring.LibUring#registerBuffers}.
     *
     * <p><b>Note:</b> The returned array should not be modified.
     *
     * @return array of all buffers in the pool (length equals {@link #capacity()})
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "Backend registration requires access to the pool's buffer objects.")
    public RegisteredBuffer[] getAllBuffers() {
        return buffers.clone();
    }

    /**
     * Returns the raw memory segment backing a buffer.
     *
     * <p>This method is used by the I/O backend to access the underlying memory for registration
     * and I/O operations.
     *
     * @param buffer the buffer to get the segment from
     * @return the memory segment backing the buffer
     */
    public MemorySegment getBufferSegment(RegisteredBuffer buffer) {
        return buffer.segment();
    }

    @Override
    public void close() {
        if (closed) {
            return; // Idempotent
        }

        closed = true;
        availableBuffers.clear();

        // Close the arena, which invalidates all memory segments.
        // Any subsequent access to buffer segments will throw IllegalStateException.
        arena.close();
    }

    /**
     * Concrete implementation of {@link RegisteredBuffer}.
     *
     * <p>This inner class manages an individual buffer's state, including position/limit tracking
     * and pool lifecycle management. Each buffer maintains a reference back to its parent pool for
     * release operations.
     */
    private static final class RegisteredBufferImpl implements RegisteredBuffer {

        /** The buffer's index in the pool's buffer array (0-based). */
        private final int index;

        /** The memory segment providing direct access to off-heap memory. */
        private final MemorySegment segment;

        /** Reference to the parent pool for release operations. */
        private final RegisteredBufferPoolImpl pool;

        /** Current read/write position in the buffer. */
        private volatile long position;

        /** Upper bound for read/write operations. */
        private volatile long limit;

        /** Flag indicating whether this buffer is currently acquired. */
        private volatile boolean inUse;

        /** User-defined token for tracking through I/O operations. */
        private volatile long token;

        /**
         * Creates a new buffer implementation.
         *
         * @param index the buffer's index in the pool
         * @param segment the memory segment backing this buffer
         * @param pool the parent pool
         */
        RegisteredBufferImpl(int index, MemorySegment segment, RegisteredBufferPoolImpl pool) {
            this.index = index;
            this.segment = segment;
            this.pool = pool;
            this.limit = segment.byteSize();
            this.position = 0;
            this.inUse = false;
        }

        @Override
        public MemorySegment segment() {
            return segment;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public long capacity() {
            return segment.byteSize();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public void position(long position) {
            if (position < 0 || position > limit) {
                throw new IllegalArgumentException("Invalid position: " + position);
            }
            this.position = position;
        }

        @Override
        public long limit() {
            return limit;
        }

        @Override
        public void limit(long limit) {
            if (limit < 0 || limit > capacity()) {
                throw new IllegalArgumentException("Invalid limit: " + limit);
            }
            this.limit = limit;
            if (position > limit) {
                position = limit;
            }
        }

        @Override
        public RegisteredBuffer clear() {
            position = 0;
            limit = capacity();
            return this;
        }

        @Override
        public RegisteredBuffer flip() {
            limit = position;
            position = 0;
            return this;
        }

        @Override
        public long remaining() {
            return limit - position;
        }

        @Override
        public boolean hasRemaining() {
            return position < limit;
        }

        @Override
        public boolean isInUse() {
            return inUse;
        }

        @Override
        public void setToken(long token) {
            this.token = token;
        }

        @Override
        public long getToken() {
            return token;
        }

        /**
         * Marks this buffer as in-use (acquired from pool). Called by the pool during acquire
         * operations.
         */
        void markInUse() {
            this.inUse = true;
        }

        /**
         * Marks this buffer as available (released to pool). Called by the pool during release
         * operations.
         */
        void markAvailable() {
            this.inUse = false;
        }

        @Override
        public Arena arena() {
            return pool.arena;
        }

        @Override
        public void close() {
            pool.release(this);
        }
    }
}

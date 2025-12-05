package express.mvp.myra.transport.buffer;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.IntConsumer;

/**
 * Lock-free implementation of {@link BufferRef} with atomic reference counting.
 *
 * <p>This implementation uses {@link VarHandle} for lock-free CAS operations on the reference
 * count, providing thread-safe retain/release without blocking.
 *
 * <h2>Memory Layout</h2>
 *
 * <ul>
 *   <li><b>segment:</b> Immutable reference to off-heap memory
 *   <li><b>address:</b> Cached native address for fast access
 *   <li><b>poolIndex:</b> Index in the parent pool's buffer array
 *   <li><b>registrationId:</b> io_uring buffer registration ID
 *   <li><b>refCount:</b> Atomic reference count (volatile int)
 *   <li><b>length:</b> Current valid data length (mutable)
 * </ul>
 *
 * <h2>Reference Count States</h2>
 *
 * <ul>
 *   <li><b>0:</b> Buffer is in pool (available for acquisition)
 *   <li><b>1:</b> Buffer is acquired by one owner
 *   <li><b>&gt;1:</b> Buffer is shared across multiple owners
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link #retain()} and {@link #release()} use CAS loops for lock-free atomic updates. The
 * {@link #length} field is not synchronized and should only be modified by the current owner.
 *
 * @see BufferRef
 * @see LockFreeBufferPool
 */
public final class BufferRefImpl implements BufferRef {

    /**
     * VarHandle for atomic CAS operations on refCount field. Initialized in static block for
     * fail-fast behavior.
     */
    private static final VarHandle REF_COUNT_VH;

    static {
        try {
            REF_COUNT_VH =
                    MethodHandles.lookup()
                            .findVarHandle(BufferRefImpl.class, "refCount", int.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The underlying off-heap memory segment (immutable). */
    private final MemorySegment segment;

    /** Cached native address for fast access (immutable). */
    private final long address;

    /** Index in the parent pool's buffer array (immutable). */
    private final int poolIndex;

    /** io_uring buffer registration ID (immutable). */
    private final short registrationId;

    /** Callback invoked when refCount reaches 0 to return buffer to pool. */
    private final IntConsumer releaseAction;

    /** Atomic reference count (0 = in pool, &gt;0 = acquired). */
    private volatile int refCount;

    /** Tracks if this buffer has been returned to pool (debug flag). */
    private volatile boolean inPool;

    /** Length of valid data in the buffer (mutable, not synchronized). */
    private int length;

    /**
     * Creates a new buffer reference.
     *
     * <p>Initially created with refCount=0 (in pool state). Call {@link #reset()} when acquiring
     * from pool.
     *
     * @param segment the memory segment backing this buffer
     * @param poolIndex the buffer's index in the pool
     * @param registrationId the io_uring registration ID
     * @param releaseAction callback to return buffer to pool
     */
    public BufferRefImpl(
            MemorySegment segment, int poolIndex, short registrationId, IntConsumer releaseAction) {
        this.segment = segment;
        this.address = segment.address();
        this.poolIndex = poolIndex;
        this.registrationId = registrationId;
        this.releaseAction = releaseAction;
        this.refCount = 0;
        this.inPool = true;  // Initially in pool
    }

    /**
     * Resets the buffer for reuse after acquisition from pool.
     *
     * <p><b>Note:</b> Should only be called by the pool during acquire. Sets refCount to 1 and
     * length to 0. Uses CAS to ensure the buffer was actually in the pool (refCount=0).
     *
     * @throws IllegalStateException if refCount was not 0 (indicates a bug)
     */
    public void reset() {
        // Use CAS to ensure we're transitioning from 0 -> 1
        // This catches double-acquire bugs
        if (!REF_COUNT_VH.compareAndSet(this, 0, 1)) {
            int current = refCount;
            throw new IllegalStateException(
                    "Buffer reset() called but refCount was " + current + " (expected 0), poolIndex=" + poolIndex);
        }
        if (!inPool) {
            throw new IllegalStateException(
                    "Buffer reset() called but inPool=false, poolIndex=" + poolIndex);
        }
        this.inPool = false;
        this.length = 0;
    }

    @Override
    public MemorySegment segment() {
        return segment;
    }

    @Override
    public long address() {
        return address;
    }

    @Override
    public int poolIndex() {
        return poolIndex;
    }

    @Override
    public short registrationId() {
        return registrationId;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public void length(int newLength) {
        this.length = newLength;
    }

    @Override
    public void retain() {
        int c;
        do {
            c = refCount;
            if (c <= 0) {
                throw new IllegalStateException(
                        "Cannot retain a released buffer (refCount=" + c + ")");
            }
        } while (!REF_COUNT_VH.compareAndSet(this, c, c + 1));
    }

    @Override
    public void release() {
        int c;
        do {
            c = refCount;
            if (c <= 0) {
                throw new IllegalStateException("Buffer already released (refCount=" + c + "), poolIndex=" + poolIndex);
            }
        } while (!REF_COUNT_VH.compareAndSet(this, c, c - 1));

        if (c == 1) {
            // RefCount dropped to 0
            if (inPool) {
                throw new IllegalStateException(
                        "Buffer release() would return to pool but inPool=true, poolIndex=" + poolIndex);
            }
            this.inPool = true;
            releaseAction.accept(poolIndex);
        }
    }

    @Override
    public String toString() {
        return "BufferRef{idx=" + poolIndex + ", len=" + length + ", ref=" + refCount + "}";
    }
}

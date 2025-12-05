package express.mvp.myra.transport.buffer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buffer pool wrapper providing timeout-based acquisition and metrics tracking.
 *
 * <p>This class wraps a {@link LockFreeBufferPool} to add blocking acquisition with timeouts,
 * efficient waiting using condition variables, and comprehensive metrics tracking.
 *
 * <h2>Acquisition Modes</h2>
 *
 * <ul>
 *   <li>{@link #tryAcquire()} - Non-blocking, returns immediately
 *   <li>{@link #acquireWithTimeout(Duration)} - Blocks up to specified timeout
 *   <li>{@link #awaitAvailable(Duration)} - Waits for availability without acquiring
 * </ul>
 *
 * <h2>Efficient Waiting</h2>
 *
 * <p>Unlike busy-spin approaches, this implementation uses a {@link Condition} variable
 * for efficient waiting. Threads waiting for buffers are parked and only woken when:
 * <ul>
 *   <li>A buffer is released back to the pool
 *   <li>The timeout expires
 *   <li>The thread is interrupted
 * </ul>
 *
 * <h2>Metrics</h2>
 *
 * <p>The pool tracks acquisition statistics including:
 * <ul>
 *   <li>Total, successful, and failed acquisition counts
 *   <li>Average and maximum wait times
 *   <li>Current availability
 * </ul>
 *
 * <p>Use {@link #metrics()} to retrieve a snapshot of current metrics.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * try (TimedBufferPool pool = new TimedBufferPool(256, 65536)) {
 *     // Non-blocking acquisition
 *     BufferRef buf = pool.tryAcquire();
 *     if (buf != null) {
 *         try {
 *             // Use buffer...
 *         } finally {
 *             buf.release(); // Standard release via BufferRef
 *         }
 *     }
 *
 *     // Blocking acquisition with timeout
 *     BufferRef buf2 = pool.acquireWithTimeout(Duration.ofMillis(100));
 *     if (buf2 != null) {
 *         try {
 *             // Use buffer...
 *         } finally {
 *             buf2.release(); // Standard release via BufferRef
 *         }
 *     } else {
 *         // Handle timeout
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. The underlying lock-free pool handles concurrent
 * acquisitions efficiently, while a lightweight lock is used only for condition
 * variable signaling during waits.
 *
 * <h2>Release Signaling</h2>
 *
 * <p>To enable efficient waiting, call {@link #signalRelease()} after releasing a buffer
 * back to the pool via {@link BufferRef#release()}. Alternatively, use the
 * {@link #releaseAndSignal(BufferRef)} convenience method which does both.
 *
 * @see LockFreeBufferPool
 * @see BufferPoolMetrics
 * @see BufferRef
 */
public class TimedBufferPool implements AutoCloseable {

    /** The underlying lock-free buffer pool. */
    private final LockFreeBufferPool delegate;

    /** Lock used for condition variable signaling (not for acquisition). */
    private final ReentrantLock waitLock;

    /** Condition signaled when a buffer becomes available. */
    private final Condition bufferAvailable;

    /** Whether this pool owns the delegate (and should close it). */
    private final boolean ownsDelegate;

    // Metrics counters using lock-free atomics for high concurrency
    private final LongAdder totalAcquisitions = new LongAdder();
    private final LongAdder successfulAcquisitions = new LongAdder();
    private final LongAdder failedAcquisitions = new LongAdder();
    private final LongAdder totalWaitTimeNanos = new LongAdder();
    private final AtomicLong maxWaitTimeNanos = new AtomicLong(0);
    private final LongAdder successfulWaitCount = new LongAdder();

    /**
     * Creates a new timed buffer pool with the specified capacity.
     *
     * <p>This constructor creates a new underlying {@link LockFreeBufferPool}.
     *
     * @param count the number of buffers (must be a power of 2)
     * @param bufferSize the size of each buffer in bytes
     * @throws IllegalArgumentException if count is not a power of 2
     */
    public TimedBufferPool(int count, int bufferSize) {
        this(new LockFreeBufferPool(count, bufferSize), true);
    }

    /**
     * Creates a new timed buffer pool wrapping an existing pool.
     *
     * <p>The wrapped pool will not be closed when this TimedBufferPool is closed.
     *
     * @param delegate the underlying buffer pool to wrap
     */
    public TimedBufferPool(LockFreeBufferPool delegate) {
        this(delegate, false);
    }

    /**
     * Internal constructor specifying ownership.
     *
     * @param delegate the underlying buffer pool
     * @param ownsDelegate whether to close the delegate on close()
     */
    private TimedBufferPool(LockFreeBufferPool delegate, boolean ownsDelegate) {
        this.delegate = delegate;
        this.ownsDelegate = ownsDelegate;
        this.waitLock = new ReentrantLock();
        this.bufferAvailable = waitLock.newCondition();
    }

    /**
     * Attempts to acquire a buffer without blocking.
     *
     * <p>This method returns immediately, either with a buffer or {@code null} if none
     * are available. Equivalent to calling the underlying pool's acquire method directly.
     *
     * @return a BufferRef with refCount=1, or {@code null} if pool is empty
     */
    public BufferRef tryAcquire() {
        totalAcquisitions.increment();
        BufferRef buf = delegate.acquire();
        if (buf != null) {
            successfulAcquisitions.increment();
        } else {
            failedAcquisitions.increment();
        }
        return buf;
    }

    /**
     * Acquires a buffer, blocking up to the specified timeout if none are available.
     *
     * <p>This method first attempts a non-blocking acquisition. If unsuccessful, it waits
     * using a condition variable (not busy-spin) for up to the specified duration.
     *
     * <p>Wait time is tracked for metrics. On success, the wait time is recorded; on
     * timeout, it's counted as a failed acquisition.
     *
     * @param timeout the maximum time to wait for a buffer
     * @return a BufferRef with refCount=1, or {@code null} if timeout expired
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if timeout is null
     */
    public BufferRef acquireWithTimeout(Duration timeout) throws InterruptedException {
        if (timeout == null) {
            throw new NullPointerException("timeout must not be null");
        }

        totalAcquisitions.increment();
        long startNanos = System.nanoTime();

        // Fast path: try immediate acquisition
        BufferRef buf = delegate.acquire();
        if (buf != null) {
            successfulAcquisitions.increment();
            recordWaitTime(0);
            return buf;
        }

        // Slow path: wait for buffer availability
        long remainingNanos = timeout.toNanos();
        waitLock.lock();
        try {
            while (remainingNanos > 0) {
                // Try to acquire before waiting
                buf = delegate.acquire();
                if (buf != null) {
                    long waitTime = System.nanoTime() - startNanos;
                    successfulAcquisitions.increment();
                    recordWaitTime(waitTime);
                    return buf;
                }

                // Wait for signal or timeout
                remainingNanos = bufferAvailable.awaitNanos(remainingNanos);
            }

            // Final attempt after timeout
            buf = delegate.acquire();
            if (buf != null) {
                long waitTime = System.nanoTime() - startNanos;
                successfulAcquisitions.increment();
                recordWaitTime(waitTime);
                return buf;
            }

            // Timeout expired
            failedAcquisitions.increment();
            return null;
        } finally {
            waitLock.unlock();
        }
    }

    /**
     * Waits for a buffer to become available without acquiring it.
     *
     * <p>This is useful for scenarios where you want to check availability before
     * committing to an acquisition, or for implementing custom retry logic.
     *
     * <p>Note: Between this method returning {@code true} and a subsequent acquisition
     * attempt, another thread may acquire the available buffer.
     *
     * @param timeout the maximum time to wait
     * @return {@code true} if a buffer is available, {@code false} if timeout expired
     * @throws InterruptedException if the current thread is interrupted while waiting
     * @throws NullPointerException if timeout is null
     */
    public boolean awaitAvailable(Duration timeout) throws InterruptedException {
        if (timeout == null) {
            throw new NullPointerException("timeout must not be null");
        }

        // Check immediate availability
        if (delegate.available() > 0) {
            return true;
        }

        long remainingNanos = timeout.toNanos();
        waitLock.lock();
        try {
            while (remainingNanos > 0) {
                if (delegate.available() > 0) {
                    return true;
                }
                remainingNanos = bufferAvailable.awaitNanos(remainingNanos);
            }
            return delegate.available() > 0;
        } finally {
            waitLock.unlock();
        }
    }

    /**
     * Releases a buffer and signals waiting threads.
     *
     * <p>This is a convenience method that combines {@link BufferRef#release()} with
     * {@link #signalRelease()}. Use this when you want waiters to be notified.
     *
     * <p><b>Important:</b> Only call this if the buffer's refCount will drop to 0,
     * otherwise use {@link BufferRef#release()} directly.
     *
     * @param buf the buffer to release
     * @throws NullPointerException if buf is null
     */
    public void releaseAndSignal(BufferRef buf) {
        if (buf == null) {
            throw new NullPointerException("buf must not be null");
        }
        buf.release();
        signalRelease();
    }

    /**
     * Signals waiting threads that a buffer may be available.
     *
     * <p>Call this method after releasing a buffer back to the pool (when refCount reaches 0)
     * to wake up threads waiting in {@link #acquireWithTimeout(Duration)} or
     * {@link #awaitAvailable(Duration)}.
     *
     * <p>It is safe to call this method even if no buffer was actually released;
     * waiters will simply re-check availability and continue waiting if necessary.
     */
    public void signalRelease() {
        waitLock.lock();
        try {
            bufferAvailable.signalAll();
        } finally {
            waitLock.unlock();
        }
    }

    /**
     * Records wait time for metrics.
     *
     * @param waitNanos the wait time in nanoseconds
     */
    private void recordWaitTime(long waitNanos) {
        if (waitNanos > 0) {
            totalWaitTimeNanos.add(waitNanos);
            successfulWaitCount.increment();

            // Update max using CAS loop
            long currentMax;
            do {
                currentMax = maxWaitTimeNanos.get();
                if (waitNanos <= currentMax) {
                    break;
                }
            } while (!maxWaitTimeNanos.compareAndSet(currentMax, waitNanos));
        }
    }

    /**
     * Returns a snapshot of current pool metrics.
     *
     * <p>The returned metrics are a point-in-time snapshot. The values may be
     * slightly inconsistent if concurrent operations are in progress, but this
     * is acceptable for monitoring purposes.
     *
     * @return current metrics snapshot
     */
    public BufferPoolMetrics metrics() {
        long total = totalAcquisitions.sum();
        long successful = successfulAcquisitions.sum();
        long failed = failedAcquisitions.sum();
        long totalWait = totalWaitTimeNanos.sum();
        long waitCount = successfulWaitCount.sum();
        long maxWait = maxWaitTimeNanos.get();

        long avgWait = waitCount > 0 ? totalWait / waitCount : 0;

        return new BufferPoolMetrics(
                total,
                successful,
                failed,
                avgWait,
                maxWait,
                delegate.available(),
                delegate.capacity());
    }

    /**
     * Resets all metrics counters to zero.
     *
     * <p>This is useful for periodic metric collection windows or testing.
     */
    public void resetMetrics() {
        totalAcquisitions.reset();
        successfulAcquisitions.reset();
        failedAcquisitions.reset();
        totalWaitTimeNanos.reset();
        successfulWaitCount.reset();
        maxWaitTimeNanos.set(0);
    }

    /**
     * Returns the total capacity of the pool.
     *
     * @return the number of buffers in the pool
     */
    public int capacity() {
        return delegate.capacity();
    }

    /**
     * Returns the number of currently available buffers.
     *
     * @return the available buffer count
     */
    public int available() {
        return delegate.available();
    }

    /**
     * Returns the underlying lock-free buffer pool.
     *
     * <p>Direct access to the delegate is provided for advanced use cases
     * where the lock-free acquire/release is preferred over timed acquisition.
     *
     * @return the underlying buffer pool
     */
    public LockFreeBufferPool delegate() {
        return delegate;
    }

    /**
     * Closes the pool and releases all resources.
     *
     * <p>If this pool was created with a count and bufferSize (owns the delegate),
     * the underlying pool is closed. If wrapping an existing pool, it remains open.
     *
     * <p>After closing, any waiting threads will be interrupted.
     */
    @Override
    public void close() {
        // Wake up any waiting threads
        waitLock.lock();
        try {
            bufferAvailable.signalAll();
        } finally {
            waitLock.unlock();
        }

        if (ownsDelegate) {
            delegate.close();
        }
    }
}

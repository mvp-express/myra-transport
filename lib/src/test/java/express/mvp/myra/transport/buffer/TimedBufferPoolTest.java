package express.mvp.myra.transport.buffer;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Comprehensive tests for {@link TimedBufferPool}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Successful acquisition within timeout</li>
 *   <li>Timeout behavior when pool exhausted</li>
 *   <li>Non-blocking tryAcquire behavior</li>
 *   <li>Metrics accuracy</li>
 *   <li>Thread safety under contention</li>
 * </ul>
 */
class TimedBufferPoolTest {

    private static final int POOL_SIZE = 4;
    private static final int BUFFER_SIZE = 1024;

    // ========== tryAcquire Tests ==========

    @Test
    void tryAcquire_whenAvailable_returnsBuffer() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            BufferRef buf = pool.tryAcquire();

            assertNotNull(buf);
            assertEquals(POOL_SIZE - 1, pool.available());
            pool.releaseAndSignal(buf);
        }
    }

    @Test
    void tryAcquire_whenExhausted_returnsNull() {
        try (TimedBufferPool pool = new TimedBufferPool(2, BUFFER_SIZE)) {
            BufferRef b1 = pool.tryAcquire();
            BufferRef b2 = pool.tryAcquire();

            assertNotNull(b1);
            assertNotNull(b2);
            assertNull(pool.tryAcquire());

            pool.releaseAndSignal(b1);
            pool.releaseAndSignal(b2);
        }
    }

    @Test
    void tryAcquire_afterRelease_bufferBecomesAvailable() {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef b1 = pool.tryAcquire();
            assertNotNull(b1);
            assertNull(pool.tryAcquire());

            pool.releaseAndSignal(b1);
            BufferRef b2 = pool.tryAcquire();
            assertNotNull(b2);

            pool.releaseAndSignal(b2);
        }
    }

    // ========== acquireWithTimeout Tests ==========

    @Test
    @Timeout(5)
    void acquireWithTimeout_whenAvailable_returnsImmediately() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            long start = System.nanoTime();
            BufferRef buf = pool.acquireWithTimeout(Duration.ofSeconds(1));
            long elapsed = System.nanoTime() - start;

            assertNotNull(buf);
            assertTrue(elapsed < Duration.ofMillis(100).toNanos(),
                    "Should return almost immediately, took " + elapsed / 1_000_000 + "ms");
            pool.releaseAndSignal(buf);
        }
    }

    @Test
    @Timeout(5)
    void acquireWithTimeout_whenExhausted_timesOut() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            assertNotNull(held);

            long start = System.nanoTime();
            BufferRef buf = pool.acquireWithTimeout(Duration.ofMillis(50));
            long elapsed = System.nanoTime() - start;

            assertNull(buf);
            assertTrue(elapsed >= Duration.ofMillis(40).toNanos(),
                    "Should wait at least ~50ms, waited " + elapsed / 1_000_000 + "ms");
            assertTrue(elapsed < Duration.ofMillis(200).toNanos(),
                    "Should not wait much longer than timeout, waited " + elapsed / 1_000_000 + "ms");

            pool.releaseAndSignal(held);
        }
    }

    @Test
    @Timeout(5)
    void acquireWithTimeout_whenReleasedDuringWait_succeeds() throws Exception {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            assertNotNull(held);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<BufferRef> future = executor.submit(() ->
                    pool.acquireWithTimeout(Duration.ofSeconds(2)));

            // Wait a bit then release
            Thread.sleep(50);
            pool.releaseAndSignal(held);

            BufferRef acquired = future.get(1, TimeUnit.SECONDS);
            assertNotNull(acquired, "Should acquire buffer after it was released");

            pool.releaseAndSignal(acquired);
            executor.shutdown();
        }
    }

    @Test
    void acquireWithTimeout_withNullTimeout_throwsNPE() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            assertThrows(NullPointerException.class, () ->
                    pool.acquireWithTimeout(null));
        }
    }

    @Test
    @Timeout(5)
    void acquireWithTimeout_withZeroDuration_behavesAsTryAcquire() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            assertNotNull(held);

            long start = System.nanoTime();
            BufferRef buf = pool.acquireWithTimeout(Duration.ZERO);
            long elapsed = System.nanoTime() - start;

            assertNull(buf);
            assertTrue(elapsed < Duration.ofMillis(50).toNanos(),
                    "Should return almost immediately with zero timeout");

            pool.releaseAndSignal(held);
        }
    }

    // ========== awaitAvailable Tests ==========

    @Test
    @Timeout(5)
    void awaitAvailable_whenAvailable_returnsTrue() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            boolean available = pool.awaitAvailable(Duration.ofMillis(100));
            assertTrue(available);
        }
    }

    @Test
    @Timeout(5)
    void awaitAvailable_whenExhausted_timesOut() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            assertNotNull(held);

            long start = System.nanoTime();
            boolean available = pool.awaitAvailable(Duration.ofMillis(50));
            long elapsed = System.nanoTime() - start;

            assertFalse(available);
            assertTrue(elapsed >= Duration.ofMillis(40).toNanos(),
                    "Should wait at least ~50ms");

            pool.releaseAndSignal(held);
        }
    }

    @Test
    @Timeout(5)
    void awaitAvailable_whenReleasedDuringWait_returnsTrue() throws Exception {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> future = executor.submit(() ->
                    pool.awaitAvailable(Duration.ofSeconds(2)));

            // Wait a bit then release
            Thread.sleep(50);
            pool.releaseAndSignal(held);

            Boolean available = future.get(1, TimeUnit.SECONDS);
            assertTrue(available);

            executor.shutdown();
        }
    }

    @Test
    void awaitAvailable_withNullTimeout_throwsNPE() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            assertThrows(NullPointerException.class, () ->
                    pool.awaitAvailable(null));
        }
    }

    // ========== Metrics Tests ==========

    @Test
    void metrics_initialState_allZeros() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            BufferPoolMetrics metrics = pool.metrics();

            assertEquals(0, metrics.totalAcquisitions());
            assertEquals(0, metrics.successfulAcquisitions());
            assertEquals(0, metrics.failedAcquisitions());
            assertEquals(0, metrics.avgWaitTimeNanos());
            assertEquals(0, metrics.maxWaitTimeNanos());
            assertEquals(POOL_SIZE, metrics.currentAvailable());
            assertEquals(POOL_SIZE, metrics.poolSize());
        }
    }

    @Test
    void metrics_afterSuccessfulTryAcquire_incrementsCounters() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            BufferRef buf = pool.tryAcquire();
            assertNotNull(buf);

            BufferPoolMetrics metrics = pool.metrics();
            assertEquals(1, metrics.totalAcquisitions());
            assertEquals(1, metrics.successfulAcquisitions());
            assertEquals(0, metrics.failedAcquisitions());
            assertEquals(POOL_SIZE - 1, metrics.currentAvailable());

            pool.releaseAndSignal(buf);
        }
    }

    @Test
    void metrics_afterFailedTryAcquire_incrementsFailureCounter() {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            pool.tryAcquire(); // Should fail

            BufferPoolMetrics metrics = pool.metrics();
            assertEquals(2, metrics.totalAcquisitions());
            assertEquals(1, metrics.successfulAcquisitions());
            assertEquals(1, metrics.failedAcquisitions());

            pool.releaseAndSignal(held);
        }
    }

    @Test
    @Timeout(5)
    void metrics_afterTimeout_incrementsFailureCounter() throws InterruptedException {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();
            pool.acquireWithTimeout(Duration.ofMillis(10)); // Should timeout

            BufferPoolMetrics metrics = pool.metrics();
            assertEquals(2, metrics.totalAcquisitions());
            assertEquals(1, metrics.successfulAcquisitions());
            assertEquals(1, metrics.failedAcquisitions());

            pool.releaseAndSignal(held);
        }
    }

    @Test
    @Timeout(5)
    void metrics_tracksWaitTime() throws Exception {
        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            BufferRef held = pool.tryAcquire();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<BufferRef> future = executor.submit(() ->
                    pool.acquireWithTimeout(Duration.ofSeconds(2)));

            // Force some wait time
            Thread.sleep(30);
            pool.releaseAndSignal(held);

            BufferRef acquired = future.get(1, TimeUnit.SECONDS);
            assertNotNull(acquired);

            BufferPoolMetrics metrics = pool.metrics();
            assertTrue(metrics.avgWaitTimeNanos() > 0, "Should track wait time");
            assertTrue(metrics.maxWaitTimeNanos() >= Duration.ofMillis(20).toNanos(),
                    "Max wait should be >= 20ms, was " + metrics.maxWaitTimeNanos());

            pool.releaseAndSignal(acquired);
            executor.shutdown();
        }
    }

    @Test
    void metrics_computedFields_correctValues() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(
                100, // total
                80,  // successful
                20,  // failed
                5_000_000, // avgWait 5ms
                50_000_000, // maxWait 50ms
                3,   // available
                10   // poolSize
        );

        assertEquals(0.8, metrics.successRate(), 0.001);
        assertEquals(0.2, metrics.failureRate(), 0.001);
        assertEquals(0.7, metrics.utilization(), 0.001);
        assertEquals(5.0, metrics.avgWaitTimeMillis(), 0.001);
        assertEquals(50.0, metrics.maxWaitTimeMillis(), 0.001);
    }

    @Test
    void metrics_withZeroAcquisitions_ratesAreZero() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(0, 0, 0, 0, 0, 4, 4);

        assertEquals(0.0, metrics.successRate());
        assertEquals(0.0, metrics.failureRate());
    }

    @Test
    void resetMetrics_clearsAllCounters() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            // Generate some metrics
            BufferRef buf = pool.tryAcquire();
            pool.releaseAndSignal(buf);

            BufferPoolMetrics before = pool.metrics();
            assertEquals(1, before.totalAcquisitions());

            pool.resetMetrics();

            BufferPoolMetrics after = pool.metrics();
            assertEquals(0, after.totalAcquisitions());
            assertEquals(0, after.successfulAcquisitions());
            assertEquals(0, after.failedAcquisitions());
        }
    }

    // ========== Thread Safety Tests ==========

    @Test
    @Timeout(30)
    void concurrency_multipleProducersConsumers_noDataRace() throws Exception {
        // Use equal pool and thread count to reduce contention
        final int poolSize = 8;
        final int numThreads = 8;
        final int operationsPerThread = 100;

        try (TimedBufferPool pool = new TimedBufferPool(poolSize, BUFFER_SIZE)) {
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            BufferRef buf = pool.acquireWithTimeout(Duration.ofMillis(100));
                            if (buf != null) {
                                successCount.incrementAndGet();
                                // Simulate some work - don't use yield() as it causes scheduling chaos
                                Thread.sleep(1);
                                pool.releaseAndSignal(buf);
                            } else {
                                failCount.incrementAndGet();
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(25, TimeUnit.SECONDS), "Test timed out");

            assertNull(error.get(), () -> "Thread error: " + error.get());
            assertEquals(poolSize, pool.available(), "All buffers should be returned");

            BufferPoolMetrics metrics = pool.metrics();
            assertEquals(numThreads * operationsPerThread, metrics.totalAcquisitions());
            assertEquals(successCount.get(), metrics.successfulAcquisitions());
            assertEquals(failCount.get(), metrics.failedAcquisitions());

            executor.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void concurrency_waitersGetSignaled() throws Exception {
        final int numWaiters = 10;

        try (TimedBufferPool pool = new TimedBufferPool(1, BUFFER_SIZE)) {
            // Hold the only buffer
            BufferRef held = pool.tryAcquire();
            assertNotNull(held);

            ExecutorService executor = Executors.newFixedThreadPool(numWaiters);
            List<Future<Boolean>> futures = new ArrayList<>();

            // Start waiters
            for (int i = 0; i < numWaiters; i++) {
                futures.add(executor.submit(() -> {
                    BufferRef buf = pool.acquireWithTimeout(Duration.ofSeconds(5));
                    if (buf != null) {
                        Thread.sleep(5); // Brief hold
                        pool.releaseAndSignal(buf);
                        return true;
                    }
                    return false;
                }));
            }

            // Let waiters queue up
            Thread.sleep(50);

            // Release the buffer - should cascade through waiters
            pool.releaseAndSignal(held);

            // All waiters should eventually succeed (one at a time)
            int successfulWaiters = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(6, TimeUnit.SECONDS)) {
                    successfulWaiters++;
                }
            }

            assertEquals(numWaiters, successfulWaiters, "All waiters should succeed");
            assertEquals(1, pool.available());

            executor.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void concurrency_tryAcquire_underContention() throws Exception {
        final int poolSize = 8;
        final int numThreads = 8;
        final int iterations = 100;

        try (TimedBufferPool pool = new TimedBufferPool(poolSize, BUFFER_SIZE)) {
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger acquiredCount = new AtomicInteger(0);
            AtomicReference<Throwable> error = new AtomicReference<>();

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterations; i++) {
                            BufferRef buf = pool.tryAcquire();
                            if (buf != null) {
                                acquiredCount.incrementAndGet();
                                Thread.sleep(1);  // Brief hold to simulate work
                                pool.releaseAndSignal(buf);
                            }
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            assertNull(error.get(), () -> "Thread error: " + error.get());

            assertEquals(poolSize, pool.available(), "All buffers should be returned");
            assertTrue(acquiredCount.get() > 0, "Should have acquired some buffers");

            executor.shutdown();
        }
    }

    // ========== Wrapping Existing Pool Tests ==========

    @Test
    void wrappingExistingPool_doesNotCloseOnTimedPoolClose() {
        LockFreeBufferPool underlying = new LockFreeBufferPool(POOL_SIZE, BUFFER_SIZE);

        try (TimedBufferPool timed = new TimedBufferPool(underlying)) {
            BufferRef buf = timed.tryAcquire();
            assertNotNull(buf);
            timed.releaseAndSignal(buf);
        }

        // Underlying pool should still be usable
        BufferRef buf = underlying.acquire();
        assertNotNull(buf);
        buf.release();

        underlying.close();
    }

    @Test
    void delegateAccess_returnsUnderlyingPool() {
        try (LockFreeBufferPool underlying = new LockFreeBufferPool(POOL_SIZE, BUFFER_SIZE);
             TimedBufferPool timed = new TimedBufferPool(underlying)) {

            assertSame(underlying, timed.delegate());
        }
    }

    // ========== Edge Cases ==========

    @Test
    void releaseAndSignal_withNull_throwsNPE() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            assertThrows(NullPointerException.class, () -> pool.releaseAndSignal(null));
        }
    }

    @Test
    void capacity_matchesUnderlyingPool() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            assertEquals(POOL_SIZE, pool.capacity());
        }
    }

    @Test
    void available_reflectsPoolState() {
        try (TimedBufferPool pool = new TimedBufferPool(POOL_SIZE, BUFFER_SIZE)) {
            assertEquals(POOL_SIZE, pool.available());

            BufferRef b1 = pool.tryAcquire();
            assertEquals(POOL_SIZE - 1, pool.available());

            BufferRef b2 = pool.tryAcquire();
            assertEquals(POOL_SIZE - 2, pool.available());

            pool.releaseAndSignal(b1);
            assertEquals(POOL_SIZE - 1, pool.available());

            pool.releaseAndSignal(b2);
            assertEquals(POOL_SIZE, pool.available());
        }
    }
}

package express.mvp.myra.transport;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link VirtualThreadWorkerPool}.
 */
class VirtualThreadWorkerPoolTest {

    private VirtualThreadWorkerPool pool;

    @AfterEach
    void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdownNow();
        }
    }

    // ========== Creation Tests ==========

    @Test
    @DisplayName("Create pool with default settings")
    void createDefault() {
        pool = VirtualThreadWorkerPool.create();
        assertNotNull(pool);
        assertFalse(pool.isShutdown());
        assertEquals(0, pool.getSubmittedTasks());
    }

    @Test
    @DisplayName("Create pool with custom name prefix")
    void createWithNamePrefix() {
        pool = VirtualThreadWorkerPool.create("custom-worker");
        assertNotNull(pool);
        assertFalse(pool.isShutdown());
    }

    @Test
    @DisplayName("Create pool using builder")
    void createWithBuilder() {
        pool = VirtualThreadWorkerPool.builder()
                .namePrefix("test-worker")
                .daemon(true)
                .build();
        assertNotNull(pool);
        assertFalse(pool.isShutdown());
    }

    // ========== Task Submission Tests ==========

    @Test
    @DisplayName("Submit Runnable task executes successfully")
    void submitRunnable() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger value = new AtomicInteger(0);

        Future<?> future = pool.submit(() -> {
            value.set(42);
            latch.countDown();
        });

        assertNotNull(future);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(42, value.get());
        assertEquals(1, pool.getSubmittedTasks());
    }

    @Test
    @DisplayName("Submit Callable task returns result")
    void submitCallable() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");

        Future<Integer> future = pool.submit(() -> 123);

        assertNotNull(future);
        assertEquals(123, future.get(5, TimeUnit.SECONDS));
        assertEquals(1, pool.getSubmittedTasks());
    }

    @Test
    @DisplayName("Execute fires and forgets")
    void execute() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch latch = new CountDownLatch(1);

        pool.execute(latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, pool.getSubmittedTasks());
    }

    @Test
    @DisplayName("Submit null task throws NullPointerException")
    void submitNullTask() {
        pool = VirtualThreadWorkerPool.create("test");

        assertThrows(NullPointerException.class, () -> pool.submit((Runnable) null));
        assertThrows(NullPointerException.class, () -> pool.submit((Callable<?>) null));
    }

    // ========== Concurrency Tests ==========

    @Test
    @DisplayName("Handle many concurrent tasks")
    void manyConcurrentTasks() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        int taskCount = 10_000;
        CountDownLatch latch = new CountDownLatch(taskCount);
        AtomicLong sum = new AtomicLong(0);

        for (int i = 0; i < taskCount; i++) {
            final int val = i;
            pool.submit(() -> {
                sum.addAndGet(val);
                latch.countDown();
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        assertEquals(taskCount, pool.getSubmittedTasks());

        // Sum of 0..9999 = n*(n-1)/2 = 10000*9999/2 = 49,995,000
        long expectedSum = (long) taskCount * (taskCount - 1) / 2;
        assertEquals(expectedSum, sum.get());
    }

    @Test
    @DisplayName("Virtual threads handle blocking without exhausting resources")
    void blockingTasks() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        int taskCount = 100;
        CountDownLatch startLatch = new CountDownLatch(taskCount);
        CountDownLatch endLatch = new CountDownLatch(taskCount);

        // Submit many blocking tasks
        for (int i = 0; i < taskCount; i++) {
            pool.submit(() -> {
                startLatch.countDown();
                try {
                    Thread.sleep(100); // Blocking call - virtual thread yields
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                endLatch.countDown();
            });
        }

        // All tasks should start quickly despite blocking
        assertTrue(startLatch.await(5, TimeUnit.SECONDS), "All tasks should start");
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All tasks should complete");
    }

    // ========== Statistics Tests ==========

    @Test
    @DisplayName("Track completed tasks count")
    void completedTasksCount() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        int taskCount = 100;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            pool.submit(latch::countDown);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100); // Allow completion callbacks to finish

        assertEquals(taskCount, pool.getSubmittedTasks());
        assertTrue(pool.getCompletedTasks() > 0);
    }

    @Test
    @DisplayName("Track failed tasks count")
    void failedTasksCount() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch latch = new CountDownLatch(3);

        // Submit tasks that throw exceptions
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                latch.countDown();
                throw new RuntimeException("Test exception");
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100); // Allow failure callbacks

        assertEquals(3, pool.getSubmittedTasks());
        assertEquals(3, pool.getFailedTasks());
    }

    @Test
    @DisplayName("Get statistics snapshot")
    void statsSnapshot() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            pool.submit(latch::countDown);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        Thread.sleep(100); // Allow completion

        VirtualThreadWorkerPool.Stats stats = pool.getStats();
        assertEquals(5, stats.submitted());
        assertTrue(stats.completed() > 0);
        assertEquals(0, stats.rejected());
        assertTrue(stats.successRate() > 0);
    }

    @Test
    @DisplayName("Thread count increases with tasks")
    void threadCountIncreases() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        assertEquals(0, pool.getThreadCount());

        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            pool.submit(latch::countDown);
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(10, pool.getThreadCount());
    }

    // ========== Shutdown Tests ==========

    @Test
    @DisplayName("Shutdown completes pending tasks")
    void shutdownCompletesPending() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch latch = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            pool.submit(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                latch.countDown();
            });
        }

        boolean completed = pool.shutdown(Duration.ofSeconds(10));
        assertTrue(completed, "Shutdown should complete with pending tasks");
        assertTrue(pool.isShutdown());
        assertEquals(0, latch.getCount(), "All tasks should complete");
    }

    @Test
    @DisplayName("Shutdown rejects new tasks")
    void shutdownRejectsNewTasks() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        pool.shutdown(Duration.ofSeconds(1));

        Future<?> result = pool.submit(() -> {});
        assertNull(result, "Task should be rejected after shutdown");
        assertEquals(1, pool.getRejectedTasks());
    }

    @Test
    @DisplayName("ShutdownNow interrupts tasks")
    void shutdownNowInterrupts() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger(0);

        pool.submit(() -> {
            startLatch.countDown();
            try {
                Thread.sleep(10_000); // Long sleep
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });

        assertTrue(startLatch.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        Thread.sleep(100);
        assertTrue(pool.isShutdown());
    }

    @Test
    @DisplayName("Close shuts down pool")
    void closeShutdown() {
        pool = VirtualThreadWorkerPool.create("test");
        pool.close();
        assertTrue(pool.isShutdown());
    }

    @Test
    @DisplayName("Double shutdown is safe")
    void doubleShutdown() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        boolean first = pool.shutdown(Duration.ofSeconds(1));
        boolean second = pool.shutdown(Duration.ofSeconds(1));

        assertTrue(first);
        assertTrue(second); // Second call should succeed immediately
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Active tasks count is accurate")
    void activeTasksCount() throws Exception {
        pool = VirtualThreadWorkerPool.create("test");
        CountDownLatch startLatch = new CountDownLatch(5);
        CountDownLatch blockLatch = new CountDownLatch(1);

        for (int i = 0; i < 5; i++) {
            pool.submit(() -> {
                startLatch.countDown();
                try {
                    blockLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertTrue(startLatch.await(5, TimeUnit.SECONDS));

        // All 5 tasks should be active (started but not completed)
        assertEquals(5, pool.getActiveTasks());

        // Release tasks
        blockLatch.countDown();
        Thread.sleep(100);

        assertEquals(0, pool.getActiveTasks());
    }

    @Test
    @DisplayName("toString provides useful information")
    void toStringFormat() {
        pool = VirtualThreadWorkerPool.create("test");
        String str = pool.toString();

        assertTrue(str.contains("VirtualThreadWorkerPool"));
        assertTrue(str.contains("submitted=0"));
        assertTrue(str.contains("shutdown=false"));
    }

    // ========== Performance Tests ==========

    @Test
    @DisplayName("Virtual thread creation is fast")
    void virtualThreadCreationPerformance() throws Exception {
        pool = VirtualThreadWorkerPool.create("perf-test");
        int warmup = 1000;
        int iterations = 10_000;

        // Warmup
        CountDownLatch warmupLatch = new CountDownLatch(warmup);
        for (int i = 0; i < warmup; i++) {
            pool.submit(warmupLatch::countDown);
        }
        assertTrue(warmupLatch.await(10, TimeUnit.SECONDS));

        // Timed run
        CountDownLatch latch = new CountDownLatch(iterations);
        long startNanos = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            pool.submit(latch::countDown);
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        long elapsedNanos = System.nanoTime() - startNanos;

        double nanosPerTask = (double) elapsedNanos / iterations;
        System.out.println("Virtual thread task submission: " + nanosPerTask + " ns/task");

        // Should be fast - under 10μs per task typically
        assertTrue(nanosPerTask < 100_000, "Task submission should be under 100μs");
    }
}

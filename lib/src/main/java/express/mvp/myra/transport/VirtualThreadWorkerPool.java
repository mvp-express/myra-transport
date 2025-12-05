package express.mvp.myra.transport;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance worker pool using Java 21+ virtual threads for I/O event processing.
 *
 * <p>This worker pool leverages Project Loom's virtual threads to handle millions of concurrent
 * tasks with minimal overhead. Unlike traditional thread pools that are constrained by OS thread
 * limits, virtual threads provide near-unlimited concurrency suitable for I/O-bound workloads.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │                     VirtualThreadWorkerPool                         │
 * ├─────────────────────────────────────────────────────────────────────┤
 * │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐               │
 * │  │ VThread-1   │   │ VThread-2   │   │ VThread-N   │  ...          │
 * │  │ (callback)  │   │ (callback)  │   │ (callback)  │               │
 * │  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘               │
 * │         │                 │                 │                       │
 * │         └─────────────────┼─────────────────┘                       │
 * │                           │                                         │
 * │                    ┌──────▼──────┐                                  │
 * │                    │  Work Queue │  (bounded or unbounded)          │
 * │                    └─────────────┘                                  │
 * └─────────────────────────────────────────────────────────────────────┘
 *                                │
 *                                ▼
 *          ┌───────────────────────────────────────┐
 *          │        Carrier Thread Pool            │
 *          │  (ForkJoinPool.commonPool() default)  │
 *          └───────────────────────────────────────┘
 * </pre>
 *
 * <h2>Key Features</h2>
 *
 * <ul>
 *   <li><b>Lightweight:</b> Virtual threads use ~1KB stack vs ~1MB for platform threads</li>
 *   <li><b>High concurrency:</b> Millions of concurrent tasks without OS limits</li>
 *   <li><b>Blocking-friendly:</b> Blocking operations yield to carrier, don't waste resources</li>
 *   <li><b>Graceful shutdown:</b> Configurable timeout for pending tasks</li>
 *   <li><b>Metrics:</b> Track submitted, completed, and active tasks</li>
 * </ul>
 *
 * <h2>When to Use Virtual Threads</h2>
 *
 * <table border="1">
 *   <caption>Virtual vs Platform Thread Guidelines</caption>
 *   <tr><th>Scenario</th><th>Recommendation</th></tr>
 *   <tr><td>I/O-bound callbacks</td><td>✅ Virtual threads</td></tr>
 *   <tr><td>Blocking network calls</td><td>✅ Virtual threads</td></tr>
 *   <tr><td>CPU-intensive compute</td><td>❌ Platform threads (dedicated cores)</td></tr>
 *   <tr><td>io_uring poll loop</td><td>❌ Platform thread (pinned to core)</td></tr>
 *   <tr><td>Synchronized locks (long-held)</td><td>⚠️ Consider platform threads</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create worker pool for callback processing
 * VirtualThreadWorkerPool workers = VirtualThreadWorkerPool.builder()
 *     .namePrefix("transport-worker")
 *     .build();
 *
 * // Submit callbacks from I/O completion handler
 * backend.poll((token, result) -> {
 *     workers.submit(() -> {
 *         // Process completion on virtual thread
 *         // Blocking here is fine - yields to carrier
 *         handler.onComplete(token, result);
 *     });
 * });
 *
 * // Graceful shutdown with timeout
 * workers.shutdown(Duration.ofSeconds(5));
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is thread-safe. Tasks can be submitted from any thread concurrently.
 *
 * <h2>Memory Model</h2>
 *
 * <p>Task submission establishes a happens-before relationship with task execution.
 * All writes before {@link #submit(Runnable)} are visible to the task.
 *
 * @see VirtualThreadFactory
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()
 */
public final class VirtualThreadWorkerPool implements AutoCloseable {

    /** The underlying executor service. */
    private final ExecutorService executor;

    /** Factory used for creating virtual threads. */
    private final VirtualThreadFactory threadFactory;

    /** Whether the pool has been shut down. */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /** Counter for submitted tasks. */
    private final AtomicLong submittedTasks = new AtomicLong(0);

    /** Counter for completed tasks. */
    private final AtomicLong completedTasks = new AtomicLong(0);

    /** Counter for failed tasks (threw exception). */
    private final AtomicLong failedTasks = new AtomicLong(0);

    /** Counter for rejected tasks (submitted after shutdown). */
    private final AtomicLong rejectedTasks = new AtomicLong(0);

    /**
     * Creates a virtual thread worker pool with the given factory.
     *
     * @param threadFactory the factory for creating virtual threads
     */
    private VirtualThreadWorkerPool(VirtualThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        this.executor = Executors.newThreadPerTaskExecutor(threadFactory);
    }

    /**
     * Creates a new builder for configuring the worker pool.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a worker pool with default settings.
     *
     * @return a new worker pool with default configuration
     */
    public static VirtualThreadWorkerPool create() {
        return builder().build();
    }

    /**
     * Creates a worker pool with the given name prefix.
     *
     * @param namePrefix the prefix for worker thread names
     * @return a new worker pool
     */
    public static VirtualThreadWorkerPool create(String namePrefix) {
        return builder().namePrefix(namePrefix).build();
    }

    /**
     * Submits a task for execution on a virtual thread.
     *
     * <p>The task will be executed asynchronously on a new virtual thread. If the pool has been
     * shut down, the task is rejected and a warning is logged.
     *
     * @param task the task to execute
     * @return a Future representing the pending completion, or null if rejected
     * @throws NullPointerException if task is null
     */
    public Future<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");

        if (shutdown.get()) {
            rejectedTasks.incrementAndGet();
            return null;
        }

        submittedTasks.incrementAndGet();

        return executor.submit(() -> {
            try {
                task.run();
                completedTasks.incrementAndGet();
            } catch (Throwable t) {
                failedTasks.incrementAndGet();
                throw t;
            }
        });
    }

    /**
     * Submits a callable task for execution on a virtual thread.
     *
     * @param <T> the result type
     * @param task the task to execute
     * @return a Future representing the pending result, or null if rejected
     * @throws NullPointerException if task is null
     */
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");

        if (shutdown.get()) {
            rejectedTasks.incrementAndGet();
            return null;
        }

        submittedTasks.incrementAndGet();

        return executor.submit(() -> {
            try {
                T result = task.call();
                completedTasks.incrementAndGet();
                return result;
            } catch (Throwable t) {
                failedTasks.incrementAndGet();
                throw t;
            }
        });
    }

    /**
     * Executes a task without returning a Future.
     *
     * <p>This is a fire-and-forget method. If you need to track completion,
     * use {@link #submit(Runnable)} instead.
     *
     * @param task the task to execute
     * @throws NullPointerException if task is null
     */
    public void execute(Runnable task) {
        submit(task);
    }

    /**
     * Initiates an orderly shutdown where previously submitted tasks are executed,
     * but no new tasks will be accepted.
     *
     * <p>Invocation has no additional effect if already shut down.
     *
     * @param timeout maximum time to wait for tasks to complete
     * @return true if all tasks completed before timeout, false otherwise
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean shutdown(Duration timeout) throws InterruptedException {
        if (!shutdown.compareAndSet(false, true)) {
            return true; // Already shutdown
        }

        executor.shutdown();
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Attempts to stop all actively executing tasks and halts processing of waiting tasks.
     *
     * <p>This method does not wait for tasks to terminate. Use {@link #shutdown(Duration)}
     * for graceful shutdown.
     */
    public void shutdownNow() {
        shutdown.set(true);
        executor.shutdownNow();
    }

    /**
     * Returns whether this pool has been shut down.
     *
     * @return true if shutdown has been initiated
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Returns whether all tasks have completed following shutdown.
     *
     * @return true if terminated
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Returns the number of tasks that have been submitted.
     *
     * @return the submitted task count
     */
    public long getSubmittedTasks() {
        return submittedTasks.get();
    }

    /**
     * Returns the number of tasks that have completed successfully.
     *
     * @return the completed task count
     */
    public long getCompletedTasks() {
        return completedTasks.get();
    }

    /**
     * Returns the number of tasks that failed with an exception.
     *
     * @return the failed task count
     */
    public long getFailedTasks() {
        return failedTasks.get();
    }

    /**
     * Returns the number of tasks rejected due to shutdown.
     *
     * @return the rejected task count
     */
    public long getRejectedTasks() {
        return rejectedTasks.get();
    }

    /**
     * Returns the approximate number of tasks currently being executed.
     *
     * @return the active task count
     */
    public long getActiveTasks() {
        return submittedTasks.get() - completedTasks.get() - failedTasks.get();
    }

    /**
     * Returns the number of virtual threads created by this pool.
     *
     * @return the thread count
     */
    public long getThreadCount() {
        return threadFactory.getThreadCount();
    }

    /**
     * Returns a snapshot of the pool's statistics.
     *
     * @return the current statistics
     */
    public Stats getStats() {
        return new Stats(
                submittedTasks.get(),
                completedTasks.get(),
                failedTasks.get(),
                rejectedTasks.get(),
                threadFactory.getThreadCount()
        );
    }

    @Override
    public void close() {
        shutdownNow();
    }

    @Override
    public String toString() {
        return "VirtualThreadWorkerPool["
                + "submitted=" + submittedTasks.get()
                + ", completed=" + completedTasks.get()
                + ", failed=" + failedTasks.get()
                + ", threads=" + threadFactory.getThreadCount()
                + ", shutdown=" + shutdown.get()
                + "]";
    }

    // ========== Builder ==========

    /**
     * Builder for creating {@link VirtualThreadWorkerPool} instances.
     */
    public static final class Builder {

        private String namePrefix = "vthread-worker";
        private boolean daemon = true;

        private Builder() {}

        /**
         * Sets the name prefix for worker threads.
         *
         * @param namePrefix the prefix for thread names
         * @return this builder
         */
        public Builder namePrefix(String namePrefix) {
            this.namePrefix = Objects.requireNonNull(namePrefix);
            return this;
        }

        /**
         * Sets whether worker threads should be daemon threads.
         *
         * <p>Note: Virtual threads don't truly support daemon flag - they don't
         * prevent JVM shutdown regardless.
         *
         * @param daemon true for daemon threads
         * @return this builder
         */
        public Builder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * Builds the worker pool.
         *
         * @return a new VirtualThreadWorkerPool
         */
        public VirtualThreadWorkerPool build() {
            return new VirtualThreadWorkerPool(
                    new VirtualThreadFactory(namePrefix, daemon)
            );
        }
    }

    // ========== Statistics Record ==========

    /**
     * Immutable snapshot of worker pool statistics.
     *
     * @param submitted number of tasks submitted
     * @param completed number of tasks completed successfully
     * @param failed number of tasks that threw exceptions
     * @param rejected number of tasks rejected after shutdown
     * @param threads number of virtual threads created
     */
    public record Stats(
            long submitted,
            long completed,
            long failed,
            long rejected,
            long threads
    ) {
        /**
         * Returns the success rate as a percentage (0-100).
         *
         * @return the success rate, or 100 if no tasks have completed
         */
        public double successRate() {
            long finished = completed + failed;
            return finished == 0 ? 100.0 : (completed * 100.0) / finished;
        }

        @Override
        public String toString() {
            return String.format(
                    "Stats[submitted=%d, completed=%d, failed=%d, rejected=%d, threads=%d, successRate=%.1f%%]",
                    submitted, completed, failed, rejected, threads, successRate()
            );
        }
    }
}

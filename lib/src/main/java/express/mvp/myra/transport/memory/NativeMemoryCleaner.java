package express.mvp.myra.transport.memory;

import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides automatic native memory cleanup using the Cleaner API.
 *
 * <p>This class ensures that native resources are released even if explicit {@code close()} is not
 * called. It uses phantom references to trigger cleanup when the owning object becomes unreachable.
 *
 * <h2>Design Rationale</h2>
 *
 * <p>While explicit resource management via {@link AutoCloseable} is preferred, this cleaner
 * provides a safety net for:
 *
 * <ul>
 *   <li>Exception paths that bypass normal cleanup
 *   <li>Complex object graphs where ownership is unclear
 *   <li>Third-party code that may not properly close resources
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * class NativeBuffer implements AutoCloseable {
 *     private final Arena arena;
 *     private final MemorySegment segment;
 *     private final Cleaner.Cleanable cleanable;
 *
 *     public NativeBuffer(long size) {
 *         this.arena = Arena.ofConfined();
 *         this.segment = arena.allocate(size);
 *
 *         // Register cleanup action - must not capture 'this'
 *         Arena arenaRef = this.arena;
 *         this.cleanable = NativeMemoryCleaner.register(this, () -> {
 *             arenaRef.close();
 *         });
 *     }
 *
 *     @Override
 *     public void close() {
 *         cleanable.clean(); // Explicit cleanup, prevents GC-triggered clean
 *     }
 * }
 * }</pre>
 *
 * <h2>Important Constraints</h2>
 *
 * <ul>
 *   <li>Cleanup actions <b>must not</b> reference the registered object (prevents GC)
 *   <li>Cleanup actions should be idempotent (may be called multiple times)
 *   <li>Cleanup runs on the cleaner thread, not the allocating thread
 * </ul>
 *
 * <h2>Statistics</h2>
 *
 * <p>This class tracks registration and cleanup statistics for monitoring and debugging.
 *
 * @see java.lang.ref.Cleaner
 * @see java.lang.foreign.Arena
 */
public final class NativeMemoryCleaner {

    /** Global cleaner instance for all native memory resources. */
    private static final Cleaner CLEANER = Cleaner.create();

    /** Counter for registrations. */
    private static final AtomicLong registrations = new AtomicLong(0);

    /** Counter for explicit cleanups (via clean()). */
    private static final AtomicLong explicitCleanups = new AtomicLong(0);

    /** Counter for GC-triggered cleanups. */
    private static final AtomicLong gcCleanups = new AtomicLong(0);

    private NativeMemoryCleaner() {
        // Utility class
    }

    /**
     * Registers an object for cleanup when it becomes phantom reachable.
     *
     * <p>The cleanup action is called either:
     * <ol>
     *   <li>Explicitly via {@link Cleaner.Cleanable#clean()}, or
     *   <li>Automatically when the object is garbage collected
     * </ol>
     *
     * <p><b>Critical:</b> The cleanup action must not reference the registered object, as this
     * would prevent garbage collection.
     *
     * @param object the object to monitor for cleanup
     * @param cleanupAction the action to run on cleanup (must not reference object)
     * @return a Cleanable that can be used for explicit cleanup
     */
    public static Cleaner.Cleanable register(Object object, Runnable cleanupAction) {
        registrations.incrementAndGet();

        // Wrap the action to track statistics
        Runnable trackedAction = new CleanupActionWrapper(cleanupAction);

        return CLEANER.register(object, trackedAction);
    }

    /**
     * Returns the total number of registrations.
     *
     * @return registration count
     */
    public static long getRegistrationCount() {
        return registrations.get();
    }

    /**
     * Returns the number of explicit cleanups (via clean()).
     *
     * @return explicit cleanup count
     */
    public static long getExplicitCleanupCount() {
        return explicitCleanups.get();
    }

    /**
     * Returns the number of GC-triggered cleanups.
     *
     * @return GC cleanup count
     */
    public static long getGcCleanupCount() {
        return gcCleanups.get();
    }

    /**
     * Returns the number of active registrations (registered - cleaned).
     *
     * @return active registration count
     */
    public static long getActiveCount() {
        return registrations.get() - explicitCleanups.get() - gcCleanups.get();
    }

    /**
     * Resets all statistics (for testing).
     */
    public static void resetStatistics() {
        registrations.set(0);
        explicitCleanups.set(0);
        gcCleanups.set(0);
    }

    /**
     * Wrapper that tracks whether cleanup was explicit or GC-triggered.
     */
    private static final class CleanupActionWrapper implements Runnable {
        private final Runnable delegate;
        private volatile boolean cleaned = false;
        private volatile boolean explicit = false;

        CleanupActionWrapper(Runnable delegate) {
            this.delegate = delegate;
        }

        /**
         * Marks this cleanup as explicit (called before GC).
         */
        void markExplicit() {
            this.explicit = true;
        }

        @Override
        public void run() {
            if (cleaned) {
                return; // Already cleaned
            }
            cleaned = true;

            // Determine if this was explicit or GC-triggered
            // If we're on the cleaner thread and not marked explicit, it's GC-triggered
            if (explicit || !Thread.currentThread().getName().contains("Cleaner")) {
                explicitCleanups.incrementAndGet();
            } else {
                gcCleanups.incrementAndGet();
            }

            delegate.run();
        }
    }

    /**
     * Creates a tracked cleanable that properly distinguishes explicit vs GC cleanup.
     *
     * @param object the object to monitor
     * @param cleanupAction the cleanup action
     * @return a cleanable that tracks cleanup type
     */
    public static TrackedCleanable registerTracked(Object object, Runnable cleanupAction) {
        CleanupActionWrapper wrapper = new CleanupActionWrapper(cleanupAction);
        Cleaner.Cleanable cleanable = CLEANER.register(object, wrapper);
        registrations.incrementAndGet();
        return new TrackedCleanable(cleanable, wrapper);
    }

    /**
     * Cleanable wrapper that properly tracks explicit cleanup.
     */
    public static final class TrackedCleanable implements AutoCloseable {
        private final Cleaner.Cleanable cleanable;
        private final CleanupActionWrapper wrapper;

        TrackedCleanable(Cleaner.Cleanable cleanable, CleanupActionWrapper wrapper) {
            this.cleanable = cleanable;
            this.wrapper = wrapper;
        }

        /**
         * Performs explicit cleanup.
         */
        public void clean() {
            wrapper.markExplicit();
            cleanable.clean();
        }

        @Override
        public void close() {
            clean();
        }
    }
}

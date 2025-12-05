package express.mvp.myra.transport.lifecycle;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates graceful shutdown of transport resources.
 *
 * <p>This coordinator manages the orderly shutdown of transports, ensuring in-flight operations
 * complete (or timeout) before resources are released. It provides a state machine for shutdown
 * phases and notifies listeners of progress.
 *
 * <h2>Shutdown Flow</h2>
 *
 * <pre>
 * 1. shutdown() called
 *    └─▶ Phase: RUNNING → DRAINING
 *        └─▶ Stop accepting new operations
 *        └─▶ Wait for in-flight operations (up to drainTimeout)
 *
 * 2. Drain complete OR timeout
 *    └─▶ Phase: DRAINING → CLOSING
 *        └─▶ Close all connections
 *        └─▶ Release backend resources
 *
 * 3. Resources released
 *    └─▶ Phase: CLOSING → TERMINATED
 *        └─▶ Notify listeners of completion
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ShutdownCoordinator coordinator = new ShutdownCoordinator();
 *
 * // Track in-flight operations
 * coordinator.operationStarted();  // When starting a send
 * coordinator.operationCompleted(); // When send completes
 *
 * // Initiate graceful shutdown
 * boolean graceful = coordinator.shutdown(
 *     Duration.ofSeconds(5),  // Max time to wait for drain
 *     () -> closeConnections(),
 *     () -> releaseResources()
 * );
 *
 * if (graceful) {
 *     System.out.println("Shutdown completed gracefully");
 * } else {
 *     System.out.println("Forced shutdown due to timeout");
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. Phase transitions use atomic operations to ensure
 * correct ordering even with concurrent shutdown requests.
 *
 * @see ShutdownPhase
 * @see ShutdownListener
 */
public final class ShutdownCoordinator {

    /** Current shutdown phase. */
    private final AtomicReference<ShutdownPhase> phase =
            new AtomicReference<>(ShutdownPhase.RUNNING);

    /** Counter for in-flight operations. */
    private final AtomicInteger inFlightOperations = new AtomicInteger(0);

    /** Total operations at start of drain (for progress reporting). */
    private volatile int drainStartCount = 0;

    /** Timestamp when shutdown was initiated. */
    private volatile long shutdownStartTimeNanos = 0;

    /** Latch signaled when all operations complete during drain. */
    private final CountDownLatch drainCompleteLatch = new CountDownLatch(1);

    /** Registered shutdown listeners. Thread-safe for concurrent modification. */
    private final List<ShutdownListener> listeners = new CopyOnWriteArrayList<>();

    /** Whether shutdown was graceful (all ops completed) or forced (timeout). */
    private volatile boolean gracefulShutdown = true;

    /**
     * Creates a new shutdown coordinator in RUNNING state.
     */
    public ShutdownCoordinator() {
        // Default state is RUNNING
    }

    /**
     * Returns the current shutdown phase.
     *
     * @return the current phase
     */
    public ShutdownPhase getPhase() {
        return phase.get();
    }

    /**
     * Checks if the coordinator is accepting new operations.
     *
     * @return true only if in RUNNING phase
     */
    public boolean isAcceptingOperations() {
        return phase.get().isAcceptingOperations();
    }

    /**
     * Checks if shutdown has completed.
     *
     * @return true if in TERMINATED phase
     */
    public boolean isTerminated() {
        return phase.get().isTerminated();
    }

    /**
     * Returns the number of in-flight operations.
     *
     * @return the count of operations not yet completed
     */
    public int getInFlightCount() {
        return inFlightOperations.get();
    }

    /**
     * Registers a listener for shutdown events.
     *
     * <p>Listeners can be added at any time. If shutdown has already started, the listener
     * will receive subsequent phase changes but not previous ones.
     *
     * @param listener the listener to register
     */
    public void addListener(ShutdownListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was found and removed
     */
    public boolean removeListener(ShutdownListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Records that an operation has started.
     *
     * <p>Call this before starting any tracked operation (send, receive, etc.).
     * Must be paired with {@link #operationCompleted()}.
     *
     * @return true if the operation was accepted, false if shutdown is in progress
     */
    public boolean operationStarted() {
        // Use a retry loop to handle race with shutdown
        while (true) {
            ShutdownPhase currentPhase = phase.get();
            if (!currentPhase.isAcceptingOperations()) {
                return false; // Reject operation during shutdown
            }

            int current = inFlightOperations.get();
            if (inFlightOperations.compareAndSet(current, current + 1)) {
                // Double-check phase hasn't changed (shutdown race)
                if (phase.get().isAcceptingOperations()) {
                    return true;
                } else {
                    // Shutdown started between our checks - roll back
                    inFlightOperations.decrementAndGet();
                    return false;
                }
            }
            // CAS failed, retry
        }
    }

    /**
     * Records that an operation has completed.
     *
     * <p>Call this when any tracked operation completes (successfully or with error).
     */
    public void operationCompleted() {
        int remaining = inFlightOperations.decrementAndGet();
        if (remaining < 0) {
            // Bug: more completions than starts
            inFlightOperations.set(0);
        }

        // If draining and all operations complete, signal the latch
        if (remaining == 0 && phase.get() == ShutdownPhase.DRAINING) {
            drainCompleteLatch.countDown();
        }

        // Report progress during drain
        if (phase.get() == ShutdownPhase.DRAINING && drainStartCount > 0) {
            for (ShutdownListener listener : listeners) {
                try {
                    listener.onDrainProgress(Math.max(0, remaining), drainStartCount);
                } catch (Exception e) {
                    // Don't let listener errors break shutdown
                }
            }
        }
    }

    /**
     * Initiates graceful shutdown.
     *
     * <p>This method blocks until shutdown is complete or the timeout expires. It progresses
     * through all shutdown phases, calling the provided callbacks at appropriate points.
     *
     * @param drainTimeout maximum time to wait for in-flight operations to complete
     * @param connectionCloser callback to close connections (called in CLOSING phase)
     * @param resourceReleaser callback to release resources (called before TERMINATED)
     * @return true if shutdown was graceful, false if forced due to timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    public boolean shutdown(
            Duration drainTimeout,
            Runnable connectionCloser,
            Runnable resourceReleaser) throws InterruptedException {

        // Transition RUNNING → DRAINING (only first caller wins)
        if (!phase.compareAndSet(ShutdownPhase.RUNNING, ShutdownPhase.DRAINING)) {
            // Already shutting down - wait for completion
            return awaitTermination(drainTimeout);
        }

        // Record shutdown start
        shutdownStartTimeNanos = System.nanoTime();
        drainStartCount = inFlightOperations.get();

        // Notify phase change
        notifyPhaseChange(ShutdownPhase.RUNNING, ShutdownPhase.DRAINING);

        // If no operations in flight, immediately signal completion
        if (drainStartCount == 0) {
            drainCompleteLatch.countDown();
        }

        // Wait for drain to complete or timeout
        boolean drained = drainCompleteLatch.await(
                drainTimeout.toMillis(), TimeUnit.MILLISECONDS);

        gracefulShutdown = drained;

        // Transition DRAINING → CLOSING
        transitionToPhase(ShutdownPhase.CLOSING);

        // Close connections
        try {
            if (connectionCloser != null) {
                connectionCloser.run();
            }
        } catch (Exception e) {
            notifyError(ShutdownPhase.CLOSING, e);
        }

        // Release resources
        try {
            if (resourceReleaser != null) {
                resourceReleaser.run();
            }
        } catch (Exception e) {
            notifyError(ShutdownPhase.CLOSING, e);
        }

        // Transition CLOSING → TERMINATED
        transitionToPhase(ShutdownPhase.TERMINATED);

        // Notify completion
        long durationMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - shutdownStartTimeNanos);
        for (ShutdownListener listener : listeners) {
            try {
                listener.onShutdownComplete(gracefulShutdown, durationMs);
            } catch (Exception e) {
                // Don't let listener errors break shutdown
            }
        }

        return gracefulShutdown;
    }

    /**
     * Initiates immediate (non-graceful) shutdown.
     *
     * <p>This skips the drain phase and immediately closes resources.
     *
     * @param connectionCloser callback to close connections
     * @param resourceReleaser callback to release resources
     */
    public void shutdownNow(Runnable connectionCloser, Runnable resourceReleaser) {
        // Force transition to CLOSING (skip DRAINING)
        ShutdownPhase previous = phase.getAndSet(ShutdownPhase.CLOSING);
        if (previous == ShutdownPhase.TERMINATED) {
            phase.set(ShutdownPhase.TERMINATED); // Already done
            return;
        }

        shutdownStartTimeNanos = System.nanoTime();
        gracefulShutdown = false;

        if (previous != ShutdownPhase.CLOSING) {
            notifyPhaseChange(previous, ShutdownPhase.CLOSING);
        }

        // Release the drain latch in case someone is waiting
        drainCompleteLatch.countDown();

        // Close and release
        try {
            if (connectionCloser != null) {
                connectionCloser.run();
            }
        } catch (Exception e) {
            notifyError(ShutdownPhase.CLOSING, e);
        }

        try {
            if (resourceReleaser != null) {
                resourceReleaser.run();
            }
        } catch (Exception e) {
            notifyError(ShutdownPhase.CLOSING, e);
        }

        // Transition to TERMINATED
        transitionToPhase(ShutdownPhase.TERMINATED);

        // Notify completion
        long durationMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - shutdownStartTimeNanos);
        for (ShutdownListener listener : listeners) {
            try {
                listener.onShutdownComplete(false, durationMs);
            } catch (Exception e) {
                // Don't let listener errors break shutdown
            }
        }
    }

    /**
     * Waits for shutdown to complete.
     *
     * @param timeout maximum time to wait
     * @return true if terminated within timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!isTerminated()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            Thread.sleep(Math.min(100, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }
        return true;
    }

    /**
     * Transitions to a new phase and notifies listeners.
     *
     * @param newPhase the phase to transition to
     */
    private void transitionToPhase(ShutdownPhase newPhase) {
        ShutdownPhase previous = phase.getAndSet(newPhase);
        if (previous != newPhase) {
            notifyPhaseChange(previous, newPhase);
        }
    }

    /**
     * Notifies listeners of a phase change.
     */
    private void notifyPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
        for (ShutdownListener listener : listeners) {
            try {
                listener.onPhaseChange(previous, current);
            } catch (Exception e) {
                // Don't let listener errors break shutdown
            }
        }
    }

    /**
     * Notifies listeners of an error.
     */
    private void notifyError(ShutdownPhase errorPhase, Throwable error) {
        for (ShutdownListener listener : listeners) {
            try {
                listener.onShutdownError(errorPhase, error);
            } catch (Exception e) {
                // Don't let listener errors break shutdown
            }
        }
    }
}

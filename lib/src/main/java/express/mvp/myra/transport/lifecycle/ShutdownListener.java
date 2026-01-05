package express.mvp.myra.transport.lifecycle;

/**
 * Callback interface for shutdown lifecycle events.
 *
 * <p>Implementations receive notifications as shutdown progresses through its phases, allowing
 * applications to perform cleanup, logging, or coordination tasks.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ShutdownCoordinator coordinator = new ShutdownCoordinator();
 * coordinator.addListener(new ShutdownListener() {
 *     @Override
 *     public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
 *         logger.info("Shutdown phase: {} -> {}", previous, current);
 *     }
 *
 *     @Override
 *     public void onDrainProgress(int remaining, int total) {
 *         logger.debug("Draining: {}/{} operations remaining", remaining, total);
 *     }
 *
 *     @Override
 *     public void onShutdownComplete(boolean graceful, long durationMs) {
 *         if (graceful) {
 *             logger.info("Graceful shutdown completed in {}ms", durationMs);
 *         } else {
 *             logger.warn("Forced shutdown after {}ms", durationMs);
 *         }
 *     }
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Listener callbacks may be invoked from different threads (e.g., the coordinator's timer thread
 * or I/O threads). Implementations must be thread-safe.
 *
 * @see ShutdownCoordinator
 * @see ShutdownPhase
 */
public interface ShutdownListener {

    /**
     * Called when the shutdown phase changes.
     *
     * <p>This is called for every phase transition, allowing listeners to track progress.
     *
     * @param previousPhase the phase being exited
     * @param currentPhase the phase being entered
     */
    void onPhaseChange(ShutdownPhase previousPhase, ShutdownPhase currentPhase);

    /**
     * Called periodically during drain phase to report progress.
     *
     * <p>This allows monitoring of how many operations are still in flight.
     *
     * @param remainingOperations number of operations still in flight
     * @param totalOperations total operations that were in flight when draining started
     */
    default void onDrainProgress(int remainingOperations, int totalOperations) {
        // Default: no-op - override to monitor drain progress
    }

    /**
     * Called when shutdown is complete.
     *
     * <p>This is the final callback, indicating the transport is now in TERMINATED state.
     *
     * @param graceful true if shutdown completed gracefully (all operations drained), false if
     *     forced due to timeout
     * @param durationMs total shutdown duration in milliseconds
     */
    void onShutdownComplete(boolean graceful, long durationMs);

    /**
     * Called if an error occurs during shutdown.
     *
     * <p>Shutdown will continue despite errors. This callback is informational.
     *
     * @param phase the phase where the error occurred
     * @param error the exception that was thrown
     */
    default void onShutdownError(ShutdownPhase phase, Throwable error) {
        // Default: no-op - override to handle errors
    }
}

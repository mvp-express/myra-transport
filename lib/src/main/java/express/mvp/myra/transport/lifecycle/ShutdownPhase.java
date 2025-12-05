package express.mvp.myra.transport.lifecycle;

/**
 * Represents the phases of transport shutdown.
 *
 * <p>Shutdown progresses through these phases in order, allowing for graceful resource cleanup
 * and completion of in-flight operations.
 *
 * <h2>Phase Transitions</h2>
 *
 * <pre>
 * RUNNING ──▶ DRAINING ──▶ CLOSING ──▶ TERMINATED
 *              │            │
 *              └────────────┴─── (timeout can force advancement)
 * </pre>
 *
 * <h2>Phase Descriptions</h2>
 *
 * <ul>
 *   <li>{@link #RUNNING}: Normal operation - accepting new operations
 *   <li>{@link #DRAINING}: Graceful shutdown initiated - no new ops, draining in-flight
 *   <li>{@link #CLOSING}: Drain complete or timeout - closing connections
 *   <li>{@link #TERMINATED}: All resources released - terminal state
 * </ul>
 *
 * @see ShutdownCoordinator
 */
public enum ShutdownPhase {

    /**
     * Normal operation phase.
     *
     * <p>Transport is fully operational, accepting new connections and I/O operations.
     */
    RUNNING(0, "Running"),

    /**
     * Draining phase - graceful shutdown initiated.
     *
     * <p>During this phase:
     * <ul>
     *   <li>New operations are rejected
     *   <li>In-flight operations are allowed to complete
     *   <li>No new connections are accepted
     * </ul>
     *
     * <p>The phase advances to {@link #CLOSING} when all in-flight operations complete
     * or the drain timeout expires.
     */
    DRAINING(1, "Draining"),

    /**
     * Closing phase - actively closing connections.
     *
     * <p>During this phase:
     * <ul>
     *   <li>All connections are being closed
     *   <li>Backend resources are being released
     *   <li>Buffer pools are being drained
     * </ul>
     */
    CLOSING(2, "Closing"),

    /**
     * Terminal phase - shutdown complete.
     *
     * <p>All resources have been released. The transport instance cannot be reused.
     */
    TERMINATED(3, "Terminated");

    private final int order;
    private final String displayName;

    ShutdownPhase(int order, String displayName) {
        this.order = order;
        this.displayName = displayName;
    }

    /**
     * Returns the numeric order of this phase for comparison.
     *
     * @return the phase order (0 = RUNNING, 3 = TERMINATED)
     */
    public int order() {
        return order;
    }

    /**
     * Returns a human-readable name for this phase.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Checks if this phase is before the specified phase.
     *
     * @param other the phase to compare with
     * @return true if this phase comes before the other
     */
    public boolean isBefore(ShutdownPhase other) {
        return this.order < other.order;
    }

    /**
     * Checks if this phase is at or after the specified phase.
     *
     * @param other the phase to compare with
     * @return true if this phase is at or after the other
     */
    public boolean isAtOrAfter(ShutdownPhase other) {
        return this.order >= other.order;
    }

    /**
     * Checks if the transport is accepting new operations.
     *
     * @return true only in {@link #RUNNING} phase
     */
    public boolean isAcceptingOperations() {
        return this == RUNNING;
    }

    /**
     * Checks if shutdown has been initiated.
     *
     * @return true if in DRAINING, CLOSING, or TERMINATED phase
     */
    public boolean isShuttingDown() {
        return this.order > RUNNING.order;
    }

    /**
     * Checks if shutdown is complete.
     *
     * @return true only in {@link #TERMINATED} phase
     */
    public boolean isTerminated() {
        return this == TERMINATED;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

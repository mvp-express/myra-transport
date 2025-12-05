package express.mvp.myra.transport;

import java.time.Instant;

/**
 * Health status and metrics for a transport instance.
 *
 * <p>This immutable class provides a snapshot of transport health including connection state,
 * throughput metrics, and error information.
 *
 * <h2>Health Metrics</h2>
 *
 * <ul>
 *   <li><b>healthy:</b> Overall health status (false if recent errors)
 *   <li><b>activeConnections:</b> Number of currently active connections
 *   <li><b>pendingOperations:</b> Operations submitted but not completed
 *   <li><b>totalBytesSent/Received:</b> Cumulative throughput counters
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * TransportHealth health = transport.getHealth();
 *
 * if (!health.isHealthy()) {
 *     System.err.println("Transport unhealthy: " + health.getErrorMessage());
 *     System.err.println("Last error at: " + health.getLastError());
 * }
 *
 * System.out.println("Active connections: " + health.getActiveConnections());
 * System.out.println("Throughput: " + health.getTotalBytesSent() + " bytes sent");
 * }</pre>
 *
 * @see Transport#getHealth()
 */
public final class TransportHealth {

    /** Overall health status (false if errors have occurred). */
    private final boolean healthy;

    /** Number of currently active connections. */
    private final int activeConnections;

    /** Number of I/O operations pending completion. */
    private final int pendingOperations;

    /** Cumulative bytes sent since transport creation. */
    private final long totalBytesSent;

    /** Cumulative bytes received since transport creation. */
    private final long totalBytesReceived;

    /** Timestamp of the most recent error, or null if none. */
    private final Instant lastError;

    /** Description of the most recent error, or null if none. */
    private final String errorMessage;

    /**
     * Creates a new health snapshot from a builder.
     *
     * @param builder the builder containing health metrics
     */
    private TransportHealth(Builder builder) {
        this.healthy = builder.healthy;
        this.activeConnections = builder.activeConnections;
        this.pendingOperations = builder.pendingOperations;
        this.totalBytesSent = builder.totalBytesSent;
        this.totalBytesReceived = builder.totalBytesReceived;
        this.lastError = builder.lastError;
        this.errorMessage = builder.errorMessage;
    }

    /**
     * Creates a new builder for constructing health snapshots.
     *
     * @return a new builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the transport is healthy.
     *
     * <p>A transport is considered unhealthy if errors have occurred since the last health check.
     *
     * @return {@code true} if healthy, {@code false} if errors have occurred
     */
    public boolean isHealthy() {
        return healthy;
    }

    /**
     * Returns the number of active connections.
     *
     * @return the active connection count
     */
    public int getActiveConnections() {
        return activeConnections;
    }

    /**
     * Returns the number of I/O operations pending completion.
     *
     * <p>High values may indicate backpressure or slow downstream processing.
     *
     * @return the pending operation count
     */
    public int getPendingOperations() {
        return pendingOperations;
    }

    /**
     * Returns the total bytes sent since transport creation.
     *
     * @return cumulative bytes sent
     */
    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Returns the total bytes received since transport creation.
     *
     * @return cumulative bytes received
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }

    /**
     * Returns the timestamp of the most recent error.
     *
     * @return the error timestamp, or {@code null} if no errors
     */
    public Instant getLastError() {
        return lastError;
    }

    /**
     * Returns the message describing the most recent error.
     *
     * @return the error message, or {@code null} if no errors
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** Builder for constructing {@link TransportHealth} instances. */
    public static final class Builder {
        private boolean healthy = true;
        private int activeConnections = 0;
        private int pendingOperations = 0;
        private long totalBytesSent = 0;
        private long totalBytesReceived = 0;
        private Instant lastError;
        private String errorMessage;

        /**
         * Sets the overall health status.
         *
         * @param healthy true if healthy
         * @return this builder for chaining
         */
        public Builder healthy(boolean healthy) {
            this.healthy = healthy;
            return this;
        }

        /**
         * Sets the active connection count.
         *
         * @param count the number of active connections
         * @return this builder for chaining
         */
        public Builder activeConnections(int count) {
            this.activeConnections = count;
            return this;
        }

        /**
         * Sets the pending operation count.
         *
         * @param count the number of pending operations
         * @return this builder for chaining
         */
        public Builder pendingOperations(int count) {
            this.pendingOperations = count;
            return this;
        }

        /**
         * Sets the total bytes sent counter.
         *
         * @param bytes cumulative bytes sent
         * @return this builder for chaining
         */
        public Builder totalBytesSent(long bytes) {
            this.totalBytesSent = bytes;
            return this;
        }

        /**
         * Sets the total bytes received counter.
         *
         * @param bytes cumulative bytes received
         * @return this builder for chaining
         */
        public Builder totalBytesReceived(long bytes) {
            this.totalBytesReceived = bytes;
            return this;
        }

        /**
         * Records an error, which also sets healthy to false.
         *
         * @param timestamp when the error occurred
         * @param message description of the error
         * @return this builder for chaining
         */
        public Builder lastError(Instant timestamp, String message) {
            this.lastError = timestamp;
            this.errorMessage = message;
            this.healthy = false;
            return this;
        }

        /**
         * Builds the health snapshot.
         *
         * @return a new immutable TransportHealth
         */
        public TransportHealth build() {
            return new TransportHealth(this);
        }
    }
}

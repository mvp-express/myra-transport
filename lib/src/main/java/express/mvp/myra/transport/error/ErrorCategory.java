package express.mvp.myra.transport.error;

/**
 * Categories of transport errors for handling and recovery decisions.
 *
 * <p>Errors are classified into categories to determine appropriate recovery actions:
 *
 * <ul>
 *   <li><b>TRANSIENT:</b> Retry immediately or after short delay
 *   <li><b>NETWORK:</b> Reconnect with backoff
 *   <li><b>PROTOCOL:</b> Typically not retryable, may need session reset
 *   <li><b>RESOURCE:</b> Wait for resource availability
 *   <li><b>FATAL:</b> Shutdown gracefully, no recovery possible
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ErrorCategory category = ErrorClassifier.classify(exception);
 * switch (category) {
 *     case TRANSIENT:
 *         // Retry with short delay
 *         break;
 *     case NETWORK:
 *         // Reconnect with exponential backoff
 *         break;
 *     case FATAL:
 *         // Shut down, notify operator
 *         break;
 * }
 * }</pre>
 *
 * @see ErrorClassifier
 * @see RetryPolicy
 */
public enum ErrorCategory {

    /**
     * Transient errors that may succeed on immediate or short-delay retry.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Timeout waiting for response
     *   <li>Server busy/overloaded
     *   <li>Temporary network congestion
     *   <li>Buffer temporarily unavailable
     * </ul>
     *
     * <p>Recommended action: Retry with short delay (milliseconds to seconds).
     */
    TRANSIENT(true, "Transient error - may succeed on retry"),

    /**
     * Network connectivity errors requiring reconnection.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Connection reset by peer
     *   <li>Connection refused
     *   <li>Host unreachable
     *   <li>DNS resolution failure
     *   <li>SSL/TLS handshake failure
     * </ul>
     *
     * <p>Recommended action: Close connection and reconnect with exponential backoff.
     */
    NETWORK(true, "Network error - reconnection required"),

    /**
     * Protocol-level errors indicating invalid communication.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Invalid message framing
     *   <li>Unexpected response type
     *   <li>Version mismatch
     *   <li>Malformed data
     * </ul>
     *
     * <p>Recommended action: Log details, may need session reset. Not typically retryable without
     * fixing the protocol issue.
     */
    PROTOCOL(false, "Protocol error - invalid communication"),

    /**
     * Resource exhaustion errors.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Out of memory
     *   <li>Buffer pool exhausted
     *   <li>Too many open files
     *   <li>Thread pool full
     * </ul>
     *
     * <p>Recommended action: Wait for resources, apply backpressure, or shed load.
     */
    RESOURCE(true, "Resource exhaustion - wait for availability"),

    /**
     * Fatal errors requiring immediate shutdown.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>JVM error (OutOfMemoryError, StackOverflowError)
     *   <li>Security violation
     *   <li>Configuration error preventing operation
     *   <li>Corrupt internal state
     * </ul>
     *
     * <p>Recommended action: Shut down gracefully, alert operators, do not retry.
     */
    FATAL(false, "Fatal error - shutdown required"),

    /**
     * Unknown or unclassified errors.
     *
     * <p>Used when the error type cannot be determined. Conservative handling treats these as
     * potentially transient.
     *
     * <p>Recommended action: Log details, limited retry with backoff.
     */
    UNKNOWN(true, "Unknown error - conservative retry");

    private final boolean retryable;
    private final String description;

    ErrorCategory(boolean retryable, String description) {
        this.retryable = retryable;
        this.description = description;
    }

    /**
     * Checks if errors in this category are generally retryable.
     *
     * <p>Note: Even if a category is retryable, specific policies may limit retries based on
     * attempt count, duration, or other factors.
     *
     * @return true if retry is generally appropriate
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Returns a human-readable description of this category.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    /**
     * Checks if this error requires connection closure/reconnection.
     *
     * @return true for NETWORK and FATAL categories
     */
    public boolean requiresReconnect() {
        return this == NETWORK || this == FATAL;
    }

    /**
     * Checks if this is a fatal error requiring shutdown.
     *
     * @return true only for FATAL category
     */
    public boolean isFatal() {
        return this == FATAL;
    }

    @Override
    public String toString() {
        return name() + " (" + description + ")";
    }
}

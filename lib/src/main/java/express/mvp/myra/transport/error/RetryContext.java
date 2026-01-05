package express.mvp.myra.transport.error;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;

/**
 * Tracks the state of retry attempts for an operation.
 *
 * <p>This class maintains information about retry attempts including counts, timing, and the last
 * error encountered. It's used by {@link RetryPolicy} to make retry decisions.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * RetryContext context = new RetryContext("send-operation", maxRetries);
 *
 * while (context.hasAttemptsRemaining()) {
 *     try {
 *         performOperation();
 *         break; // Success
 *     } catch (Exception e) {
 *         context.recordFailure(e);
 *         if (context.hasAttemptsRemaining()) {
 *             Thread.sleep(context.getNextDelayMillis());
 *         } else {
 *             throw new OperationFailedException(
 *                 "Operation failed after " + context.getAttemptCount() + " attempts", e);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is not thread-safe. Each retry sequence should use its own context instance.
 *
 * @see RetryPolicy
 */
public final class RetryContext {

    /** Identifier for the operation being retried. */
    private final String operationId;

    /** Maximum number of attempts (including initial). */
    private final int maxAttempts;

    /** Current attempt number (1-based). */
    private int attemptCount;

    /** Time of first attempt. */
    private final Instant startTime;

    /** Time of last attempt. */
    private Instant lastAttemptTime;

    /** Last error encountered. */
    private Throwable lastError;

    /** Category of last error. */
    private ErrorCategory lastErrorCategory;

    /** Total time spent in delays. */
    private long totalDelayMillis;

    /** Next delay to apply (set by policy). */
    private long nextDelayMillis;

    /**
     * Creates a new retry context.
     *
     * @param operationId identifier for the operation
     * @param maxAttempts maximum number of attempts
     */
    public RetryContext(String operationId, int maxAttempts) {
        this.operationId = operationId;
        this.maxAttempts = maxAttempts;
        this.attemptCount = 0;
        this.startTime = Instant.now();
        this.lastAttemptTime = startTime;
    }

    /**
     * Creates a context with default operation ID.
     *
     * @param maxAttempts maximum number of attempts
     */
    public RetryContext(int maxAttempts) {
        this("unknown", maxAttempts);
    }

    /**
     * Returns the operation identifier.
     *
     * @return the operation ID
     */
    public String getOperationId() {
        return operationId;
    }

    /**
     * Returns the maximum number of attempts allowed.
     *
     * @return max attempts
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Returns the current attempt count.
     *
     * @return number of attempts made (0 before first attempt)
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * Returns the number of retries (attempts minus 1).
     *
     * @return number of retries
     */
    public int getRetryCount() {
        return Math.max(0, attemptCount - 1);
    }

    /**
     * Checks if there are attempts remaining.
     *
     * @return true if more attempts are allowed
     */
    public boolean hasAttemptsRemaining() {
        return attemptCount < maxAttempts;
    }

    /**
     * Records that an attempt is starting.
     *
     * @return the new attempt number
     */
    public int startAttempt() {
        attemptCount++;
        lastAttemptTime = Instant.now();
        return attemptCount;
    }

    /**
     * Records a failed attempt.
     *
     * @param error the error that caused the failure
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Throwable is kept for diagnostics and cannot be safely copied.")
    public void recordFailure(Throwable error) {
        this.lastError = error;
        this.lastErrorCategory = ErrorClassifier.classify(error);
    }

    /**
     * Records a failed attempt with explicit category.
     *
     * @param error the error that caused the failure
     * @param category the error category
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Throwable is kept for diagnostics and cannot be safely copied.")
    public void recordFailure(Throwable error, ErrorCategory category) {
        this.lastError = error;
        this.lastErrorCategory = category;
    }

    /**
     * Returns the last error encountered.
     *
     * @return the last error, or null if no failures yet
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP",
            justification = "Throwable is exposed for diagnostics and cannot be safely copied.")
    public Throwable getLastError() {
        return lastError;
    }

    /**
     * Returns the category of the last error.
     *
     * @return the error category, or null if no failures yet
     */
    public ErrorCategory getLastErrorCategory() {
        return lastErrorCategory;
    }

    /**
     * Returns the time when retries started.
     *
     * @return the start time
     */
    public Instant getStartTime() {
        return startTime;
    }

    /**
     * Returns the time of the last attempt.
     *
     * @return the last attempt time
     */
    public Instant getLastAttemptTime() {
        return lastAttemptTime;
    }

    /**
     * Returns the total elapsed time since start.
     *
     * @return elapsed duration
     */
    public Duration getElapsedTime() {
        return Duration.between(startTime, Instant.now());
    }

    /**
     * Returns the total time spent waiting in delays.
     *
     * @return total delay time in milliseconds
     */
    public long getTotalDelayMillis() {
        return totalDelayMillis;
    }

    /**
     * Sets the delay for the next retry.
     *
     * <p>This is typically called by the retry policy.
     *
     * @param delayMillis delay in milliseconds
     */
    public void setNextDelay(long delayMillis) {
        this.nextDelayMillis = delayMillis;
    }

    /**
     * Returns the configured delay for the next retry.
     *
     * @return delay in milliseconds
     */
    public long getNextDelayMillis() {
        return nextDelayMillis;
    }

    /**
     * Records that a delay was executed.
     *
     * @param delayMillis the delay that was applied
     */
    public void recordDelay(long delayMillis) {
        this.totalDelayMillis += delayMillis;
    }

    /**
     * Checks if this is the first attempt.
     *
     * @return true if attemptCount is 0 or 1
     */
    public boolean isFirstAttempt() {
        return attemptCount <= 1;
    }

    /**
     * Checks if this is the last allowed attempt.
     *
     * @return true if no more retries are allowed after this attempt
     */
    public boolean isLastAttempt() {
        return attemptCount >= maxAttempts;
    }

    /** Resets the context for reuse. */
    public void reset() {
        this.attemptCount = 0;
        this.lastError = null;
        this.lastErrorCategory = null;
        this.totalDelayMillis = 0;
        this.nextDelayMillis = 0;
    }

    @Override
    public String toString() {
        return String.format(
                "RetryContext[op=%s, attempt=%d/%d, elapsed=%dms, lastError=%s]",
                operationId,
                attemptCount,
                maxAttempts,
                getElapsedTime().toMillis(),
                lastErrorCategory);
    }
}

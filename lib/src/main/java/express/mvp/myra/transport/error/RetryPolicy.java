package express.mvp.myra.transport.error;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Defines retry behavior for failed operations.
 *
 * <p>This class encapsulates retry policy decisions including whether to retry, delay calculation,
 * and maximum attempt limits. It supports exponential backoff with jitter for distributed systems.
 *
 * <h2>Built-in Policies</h2>
 *
 * <ul>
 *   <li>{@link #noRetry()} - Never retry
 *   <li>{@link #immediate(int)} - Retry immediately without delay
 *   <li>{@link #fixedDelay(int, Duration)} - Fixed delay between retries
 *   <li>{@link #exponentialBackoff(int, Duration, Duration)} - Exponential backoff with cap
 *   <li>{@link #exponentialBackoffWithJitter(int, Duration, Duration, double)} - With jitter
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.exponentialBackoffWithJitter(
 *     5,                      // max 5 attempts
 *     Duration.ofMillis(100), // start with 100ms
 *     Duration.ofSeconds(30), // cap at 30s
 *     0.2                     // 20% jitter
 * );
 *
 * RetryContext context = new RetryContext("connect", policy.getMaxAttempts());
 *
 * while (true) {
 *     try {
 *         context.startAttempt();
 *         connect();
 *         break;
 *     } catch (Exception e) {
 *         context.recordFailure(e);
 *         if (policy.shouldRetry(context)) {
 *             long delay = policy.calculateDelay(context);
 *             Thread.sleep(delay);
 *             context.recordDelay(delay);
 *         } else {
 *             throw new ConnectionException("Failed after retries", e);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see RetryContext
 * @see ErrorCategory
 */
public final class RetryPolicy {

    /** Maximum number of attempts. */
    private final int maxAttempts;

    /** Initial delay for first retry. */
    private final long initialDelayMillis;

    /** Maximum delay cap. */
    private final long maxDelayMillis;

    /** Backoff multiplier (1.0 = fixed delay). */
    private final double backoffMultiplier;

    /** Jitter factor (0.0-1.0). */
    private final double jitterFactor;

    /** Maximum total duration for all retries. */
    private final long maxTotalDurationMillis;

    /** Categories that should trigger retry. */
    private final boolean retryTransient;
    private final boolean retryNetwork;
    private final boolean retryResource;
    private final boolean retryUnknown;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMillis = builder.initialDelayMillis;
        this.maxDelayMillis = builder.maxDelayMillis;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.jitterFactor = builder.jitterFactor;
        this.maxTotalDurationMillis = builder.maxTotalDurationMillis;
        this.retryTransient = builder.retryTransient;
        this.retryNetwork = builder.retryNetwork;
        this.retryResource = builder.retryResource;
        this.retryUnknown = builder.retryUnknown;
    }

    /**
     * Returns the maximum number of attempts.
     *
     * @return max attempts
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Determines if a retry should be attempted.
     *
     * @param context the retry context
     * @return true if retry should be attempted
     */
    public boolean shouldRetry(RetryContext context) {
        // Check attempt count
        if (!context.hasAttemptsRemaining()) {
            return false;
        }

        // Check total duration
        if (maxTotalDurationMillis > 0 &&
            context.getElapsedTime().toMillis() >= maxTotalDurationMillis) {
            return false;
        }

        // Check error category
        ErrorCategory category = context.getLastErrorCategory();
        if (category == null) {
            return true; // No error yet, allow first attempt
        }

        return switch (category) {
            case TRANSIENT -> retryTransient;
            case NETWORK -> retryNetwork;
            case RESOURCE -> retryResource;
            case UNKNOWN -> retryUnknown;
            case PROTOCOL, FATAL -> false; // Never retry these
        };
    }

    /**
     * Calculates the delay before the next retry.
     *
     * @param context the retry context
     * @return delay in milliseconds
     */
    public long calculateDelay(RetryContext context) {
        int retryNumber = context.getRetryCount();

        // Calculate base delay with exponential backoff
        long delay;
        if (backoffMultiplier <= 1.0) {
            delay = initialDelayMillis;
        } else {
            delay = (long) (initialDelayMillis * Math.pow(backoffMultiplier, retryNumber));
        }

        // Apply cap
        delay = Math.min(delay, maxDelayMillis);

        // Apply jitter
        if (jitterFactor > 0) {
            double jitter = ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
            delay = (long) (delay * (1 + jitter));
            delay = Math.max(0, delay); // Ensure non-negative
        }

        // Update context
        context.setNextDelay(delay);

        return delay;
    }

    /**
     * Returns a policy that never retries.
     *
     * @return no-retry policy
     */
    public static RetryPolicy noRetry() {
        return new Builder()
                .maxAttempts(1)
                .build();
    }

    /**
     * Returns a policy that retries immediately without delay.
     *
     * @param maxAttempts maximum attempts
     * @return immediate retry policy
     */
    public static RetryPolicy immediate(int maxAttempts) {
        return new Builder()
                .maxAttempts(maxAttempts)
                .initialDelay(Duration.ZERO)
                .maxDelay(Duration.ZERO)
                .build();
    }

    /**
     * Returns a policy with fixed delay between retries.
     *
     * @param maxAttempts maximum attempts
     * @param delay delay between retries
     * @return fixed delay policy
     */
    public static RetryPolicy fixedDelay(int maxAttempts, Duration delay) {
        return new Builder()
                .maxAttempts(maxAttempts)
                .initialDelay(delay)
                .maxDelay(delay)
                .backoffMultiplier(1.0)
                .build();
    }

    /**
     * Returns a policy with exponential backoff.
     *
     * @param maxAttempts maximum attempts
     * @param initialDelay initial delay
     * @param maxDelay maximum delay cap
     * @return exponential backoff policy
     */
    public static RetryPolicy exponentialBackoff(
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay) {
        return new Builder()
                .maxAttempts(maxAttempts)
                .initialDelay(initialDelay)
                .maxDelay(maxDelay)
                .backoffMultiplier(2.0)
                .build();
    }

    /**
     * Returns a policy with exponential backoff and jitter.
     *
     * <p>Jitter helps prevent thundering herd problems when many clients retry simultaneously.
     *
     * @param maxAttempts maximum attempts
     * @param initialDelay initial delay
     * @param maxDelay maximum delay cap
     * @param jitterFactor jitter factor (0.0-1.0, e.g., 0.2 for ±20%)
     * @return exponential backoff with jitter policy
     */
    public static RetryPolicy exponentialBackoffWithJitter(
            int maxAttempts,
            Duration initialDelay,
            Duration maxDelay,
            double jitterFactor) {
        return new Builder()
                .maxAttempts(maxAttempts)
                .initialDelay(initialDelay)
                .maxDelay(maxDelay)
                .backoffMultiplier(2.0)
                .jitterFactor(jitterFactor)
                .build();
    }

    /**
     * Returns a builder for custom policy configuration.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link RetryPolicy}.
     */
    public static final class Builder {
        private int maxAttempts = 3;
        private long initialDelayMillis = 100;
        private long maxDelayMillis = 30_000;
        private double backoffMultiplier = 2.0;
        private double jitterFactor = 0.0;
        private long maxTotalDurationMillis = 0;
        private boolean retryTransient = true;
        private boolean retryNetwork = true;
        private boolean retryResource = true;
        private boolean retryUnknown = true;

        /**
         * Sets the maximum number of attempts.
         *
         * @param maxAttempts max attempts (must be >= 1)
         * @return this builder
         */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the initial delay before first retry.
         *
         * @param delay initial delay
         * @return this builder
         */
        public Builder initialDelay(Duration delay) {
            Objects.requireNonNull(delay, "delay");
            this.initialDelayMillis = delay.toMillis();
            return this;
        }

        /**
         * Sets the maximum delay cap.
         *
         * @param maxDelay maximum delay
         * @return this builder
         */
        public Builder maxDelay(Duration maxDelay) {
            Objects.requireNonNull(maxDelay, "maxDelay");
            this.maxDelayMillis = maxDelay.toMillis();
            return this;
        }

        /**
         * Sets the backoff multiplier.
         *
         * @param multiplier multiplier (1.0 = fixed delay, 2.0 = double each time)
         * @return this builder
         */
        public Builder backoffMultiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
            }
            this.backoffMultiplier = multiplier;
            return this;
        }

        /**
         * Sets the jitter factor.
         *
         * @param jitter jitter factor (0.0-1.0)
         * @return this builder
         */
        public Builder jitterFactor(double jitter) {
            if (jitter < 0 || jitter > 1.0) {
                throw new IllegalArgumentException("jitterFactor must be 0.0-1.0");
            }
            this.jitterFactor = jitter;
            return this;
        }

        /**
         * Sets maximum total duration for all retry attempts.
         *
         * @param duration maximum duration (0 = no limit)
         * @return this builder
         */
        public Builder maxTotalDuration(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            this.maxTotalDurationMillis = duration.toMillis();
            return this;
        }

        /**
         * Sets whether to retry transient errors.
         *
         * @param retry true to retry
         * @return this builder
         */
        public Builder retryTransient(boolean retry) {
            this.retryTransient = retry;
            return this;
        }

        /**
         * Sets whether to retry network errors.
         *
         * @param retry true to retry
         * @return this builder
         */
        public Builder retryNetwork(boolean retry) {
            this.retryNetwork = retry;
            return this;
        }

        /**
         * Sets whether to retry resource errors.
         *
         * @param retry true to retry
         * @return this builder
         */
        public Builder retryResource(boolean retry) {
            this.retryResource = retry;
            return this;
        }

        /**
         * Sets whether to retry unknown errors.
         *
         * @param retry true to retry
         * @return this builder
         */
        public Builder retryUnknown(boolean retry) {
            this.retryUnknown = retry;
            return this;
        }

        /**
         * Builds the retry policy.
         *
         * @return new policy
         */
        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}

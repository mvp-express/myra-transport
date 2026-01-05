package express.mvp.myra.transport.error;

import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RetryPolicy} and {@link RetryContext}. */
@DisplayName("RetryPolicy")
@SuppressFBWarnings(
        value = {"RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT"},
        justification = "SpotBugs rules are intentionally relaxed for test scaffolding.")
class RetryPolicyTest {

    @Nested
    @DisplayName("No retry policy")
    class NoRetryPolicyTests {

        @Test
        @DisplayName("noRetry allows only one attempt")
        void noRetry_allowsOneAttempt() {
            RetryPolicy policy = RetryPolicy.noRetry();
            assertEquals(1, policy.getMaxAttempts());
        }

        @Test
        @DisplayName("noRetry returns false for shouldRetry after first attempt")
        void noRetry_returnsFalseAfterFirstAttempt() {
            RetryPolicy policy = RetryPolicy.noRetry();
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            context.startAttempt();
            context.recordFailure(new RuntimeException("Error"));

            assertFalse(policy.shouldRetry(context));
        }
    }

    @Nested
    @DisplayName("Immediate retry policy")
    class ImmediateRetryPolicyTests {

        @Test
        @DisplayName("immediate allows specified attempts")
        void immediate_allowsSpecifiedAttempts() {
            RetryPolicy policy = RetryPolicy.immediate(3);
            assertEquals(3, policy.getMaxAttempts());
        }

        @Test
        @DisplayName("immediate calculates zero delay")
        void immediate_calculatesZeroDelay() {
            RetryPolicy policy = RetryPolicy.immediate(3);
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            context.startAttempt();
            context.recordFailure(new RuntimeException("Error"));

            assertEquals(0, policy.calculateDelay(context));
        }
    }

    @Nested
    @DisplayName("Fixed delay policy")
    class FixedDelayPolicyTests {

        @Test
        @DisplayName("fixedDelay returns constant delay")
        void fixedDelay_returnsConstantDelay() {
            RetryPolicy policy = RetryPolicy.fixedDelay(3, Duration.ofMillis(100));
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            // First retry
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            assertEquals(100, policy.calculateDelay(context));

            // Second retry
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            assertEquals(100, policy.calculateDelay(context));
        }
    }

    @Nested
    @DisplayName("Exponential backoff policy")
    class ExponentialBackoffPolicyTests {

        @Test
        @DisplayName("exponentialBackoff increases delay")
        void exponentialBackoff_increasesDelay() {
            RetryPolicy policy =
                    RetryPolicy.exponentialBackoff(
                            5, Duration.ofMillis(100), Duration.ofSeconds(10));
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            // First attempt fails
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            long delay1 = policy.calculateDelay(context);

            // Second attempt fails
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            long delay2 = policy.calculateDelay(context);

            // Third attempt fails
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            long delay3 = policy.calculateDelay(context);

            assertTrue(delay2 > delay1, "Second delay should be greater than first");
            assertTrue(delay3 > delay2, "Third delay should be greater than second");
        }

        @Test
        @DisplayName("exponentialBackoff respects max delay")
        void exponentialBackoff_respectsMaxDelay() {
            RetryPolicy policy =
                    RetryPolicy.exponentialBackoff(
                            10, Duration.ofMillis(100), Duration.ofMillis(500));
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            // Run through many retries
            for (int i = 0; i < 9; i++) {
                context.startAttempt();
                context.recordFailure(new RuntimeException());
                long delay = policy.calculateDelay(context);
                assertTrue(delay <= 500, "Delay " + delay + " should not exceed max 500ms");
            }
        }
    }

    @Nested
    @DisplayName("Exponential backoff with jitter")
    class ExponentialBackoffWithJitterTests {

        @Test
        @DisplayName("jitter varies delay")
        void jitter_variesDelay() {
            RetryPolicy policy =
                    RetryPolicy.exponentialBackoffWithJitter(
                            5, Duration.ofMillis(100), Duration.ofSeconds(10), 0.5);
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            context.startAttempt();
            context.recordFailure(new RuntimeException());

            // Run multiple times and check variance
            boolean foundDifferent = false;
            long firstDelay = policy.calculateDelay(context);

            for (int i = 0; i < 20; i++) {
                context.reset();
                context.startAttempt();
                context.recordFailure(new RuntimeException());
                long delay = policy.calculateDelay(context);
                if (delay != firstDelay) {
                    foundDifferent = true;
                    break;
                }
            }

            assertTrue(foundDifferent, "Jitter should cause delay variance");
        }

        @Test
        @DisplayName("jitter stays within bounds")
        void jitter_staysWithinBounds() {
            double jitter = 0.2;
            RetryPolicy policy =
                    RetryPolicy.exponentialBackoffWithJitter(
                            5, Duration.ofMillis(100), Duration.ofSeconds(10), jitter);
            RetryContext context = new RetryContext(policy.getMaxAttempts());

            context.startAttempt();
            context.recordFailure(new RuntimeException());

            // Expected base is 100ms, with ±20% jitter = 80-120ms
            for (int i = 0; i < 100; i++) {
                context.reset();
                context.startAttempt();
                context.recordFailure(new RuntimeException());
                long delay = policy.calculateDelay(context);
                assertTrue(
                        delay >= 80 && delay <= 120,
                        "Delay " + delay + " should be within jitter bounds");
            }
        }
    }

    @Nested
    @DisplayName("Category-based retry decisions")
    class CategoryBasedRetryTests {

        private RetryPolicy policy;

        @BeforeEach
        void setup() {
            policy =
                    RetryPolicy.builder()
                            .maxAttempts(5)
                            .initialDelay(Duration.ofMillis(100))
                            .maxDelay(Duration.ofSeconds(1))
                            .retryTransient(true)
                            .retryNetwork(true)
                            .retryResource(true)
                            .retryUnknown(false)
                            .build();
        }

        @Test
        @DisplayName("TRANSIENT errors are retried")
        void transientErrors_areRetried() {
            RetryContext context = new RetryContext(policy.getMaxAttempts());
            context.startAttempt();
            context.recordFailure(new RuntimeException("timeout"), ErrorCategory.TRANSIENT);

            assertTrue(policy.shouldRetry(context));
        }

        @Test
        @DisplayName("NETWORK errors are retried")
        void networkErrors_areRetried() {
            RetryContext context = new RetryContext(policy.getMaxAttempts());
            context.startAttempt();
            context.recordFailure(new RuntimeException("Connection reset"), ErrorCategory.NETWORK);

            assertTrue(policy.shouldRetry(context));
        }

        @Test
        @DisplayName("PROTOCOL errors are not retried")
        void protocolErrors_notRetried() {
            RetryContext context = new RetryContext(policy.getMaxAttempts());
            context.startAttempt();
            context.recordFailure(new RuntimeException("Invalid frame"), ErrorCategory.PROTOCOL);

            assertFalse(policy.shouldRetry(context));
        }

        @Test
        @DisplayName("FATAL errors are not retried")
        void fatalErrors_notRetried() {
            RetryContext context = new RetryContext(policy.getMaxAttempts());
            context.startAttempt();
            context.recordFailure(new RuntimeException("Security violation"), ErrorCategory.FATAL);

            assertFalse(policy.shouldRetry(context));
        }

        @Test
        @DisplayName("UNKNOWN errors honor configuration")
        void unknownErrors_honorConfiguration() {
            RetryContext context = new RetryContext(policy.getMaxAttempts());
            context.startAttempt();
            context.recordFailure(new RuntimeException("Something"), ErrorCategory.UNKNOWN);

            // Our policy has retryUnknown = false
            assertFalse(policy.shouldRetry(context));
        }
    }

    @Nested
    @DisplayName("Max total duration")
    class MaxTotalDurationTests {

        @Test
        @DisplayName("Stops retries when max duration exceeded")
        void stopsRetries_whenMaxDurationExceeded() throws InterruptedException {
            RetryPolicy policy =
                    RetryPolicy.builder()
                            .maxAttempts(100)
                            .initialDelay(Duration.ofMillis(10))
                            .maxDelay(Duration.ofMillis(100))
                            .maxTotalDuration(Duration.ofMillis(100))
                            .build();

            RetryContext context = new RetryContext(policy.getMaxAttempts());

            // First attempt - should allow retry
            context.startAttempt();
            context.recordFailure(new RuntimeException());
            assertTrue(policy.shouldRetry(context));

            // Wait for duration to expire
            Thread.sleep(150);

            // Should not allow retry now
            assertFalse(policy.shouldRetry(context));
        }
    }

    @Nested
    @DisplayName("Builder validation")
    class BuilderValidationTests {

        @Test
        @DisplayName("maxAttempts must be >= 1")
        void maxAttempts_mustBePositive() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RetryPolicy.builder().maxAttempts(0).build());
        }

        @Test
        @DisplayName("backoffMultiplier must be >= 1.0")
        void backoffMultiplier_mustBeAtLeastOne() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RetryPolicy.builder().backoffMultiplier(0.5).build());
        }

        @Test
        @DisplayName("jitterFactor must be 0.0-1.0")
        void jitterFactor_mustBeValid() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RetryPolicy.builder().jitterFactor(-0.1).build());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RetryPolicy.builder().jitterFactor(1.1).build());
        }
    }
}

@DisplayName("RetryContext")
class RetryContextTest {

    @Nested
    @DisplayName("Initial state")
    class InitialStateTests {

        @Test
        @DisplayName("Starts with zero attempts")
        void startsWithZeroAttempts() {
            RetryContext context = new RetryContext(3);
            assertEquals(0, context.getAttemptCount());
        }

        @Test
        @DisplayName("Has attempts remaining initially")
        void hasAttemptsRemainingInitially() {
            RetryContext context = new RetryContext(3);
            assertTrue(context.hasAttemptsRemaining());
        }

        @Test
        @DisplayName("No last error initially")
        void noLastErrorInitially() {
            RetryContext context = new RetryContext(3);
            assertNull(context.getLastError());
            assertNull(context.getLastErrorCategory());
        }

        @Test
        @DisplayName("Operation ID is set")
        void operationIdIsSet() {
            RetryContext context = new RetryContext("test-op", 3);
            assertEquals("test-op", context.getOperationId());
        }
    }

    @Nested
    @DisplayName("Attempt tracking")
    class AttemptTrackingTests {

        @Test
        @DisplayName("startAttempt increments count")
        void startAttempt_incrementsCount() {
            RetryContext context = new RetryContext(3);

            assertEquals(1, context.startAttempt());
            assertEquals(1, context.getAttemptCount());

            assertEquals(2, context.startAttempt());
            assertEquals(2, context.getAttemptCount());
        }

        @Test
        @DisplayName("Retry count is attempts minus 1")
        void retryCount_isAttemptsMinus1() {
            RetryContext context = new RetryContext(3);

            context.startAttempt();
            assertEquals(0, context.getRetryCount());

            context.startAttempt();
            assertEquals(1, context.getRetryCount());
        }

        @Test
        @DisplayName("isFirstAttempt is correct")
        void isFirstAttempt_isCorrect() {
            RetryContext context = new RetryContext(3);
            assertTrue(context.isFirstAttempt());

            context.startAttempt();
            assertTrue(context.isFirstAttempt());

            context.startAttempt();
            assertFalse(context.isFirstAttempt());
        }

        @Test
        @DisplayName("isLastAttempt is correct")
        void isLastAttempt_isCorrect() {
            RetryContext context = new RetryContext(2);

            context.startAttempt();
            assertFalse(context.isLastAttempt());

            context.startAttempt();
            assertTrue(context.isLastAttempt());
        }

        @Test
        @DisplayName("hasAttemptsRemaining exhausts correctly")
        void hasAttemptsRemaining_exhaustsCorrectly() {
            RetryContext context = new RetryContext(2);

            assertTrue(context.hasAttemptsRemaining());
            context.startAttempt();
            assertTrue(context.hasAttemptsRemaining());
            context.startAttempt();
            assertFalse(context.hasAttemptsRemaining());
        }
    }

    @Nested
    @DisplayName("Failure recording")
    class FailureRecordingTests {

        @Test
        @DisplayName("recordFailure stores error")
        void recordFailure_storesError() {
            RetryContext context = new RetryContext(3);
            RuntimeException error = new RuntimeException("Test error");

            context.recordFailure(error);

            assertSame(error, context.getLastError());
        }

        @Test
        @DisplayName("recordFailure classifies error")
        void recordFailure_classifiesError() {
            RetryContext context = new RetryContext(3);

            context.recordFailure(new java.net.ConnectException("Refused"));

            assertEquals(ErrorCategory.NETWORK, context.getLastErrorCategory());
        }

        @Test
        @DisplayName("recordFailure with explicit category")
        void recordFailure_withExplicitCategory() {
            RetryContext context = new RetryContext(3);

            context.recordFailure(new RuntimeException("Custom"), ErrorCategory.RESOURCE);

            assertEquals(ErrorCategory.RESOURCE, context.getLastErrorCategory());
        }
    }

    @Nested
    @DisplayName("Timing")
    class TimingTests {

        @Test
        @DisplayName("Tracks elapsed time")
        void tracksElapsedTime() throws InterruptedException {
            RetryContext context = new RetryContext(3);
            Thread.sleep(50);
            assertTrue(context.getElapsedTime().toMillis() >= 50);
        }

        @Test
        @DisplayName("Tracks delay time")
        void tracksDelayTime() {
            RetryContext context = new RetryContext(3);

            context.recordDelay(100);
            context.recordDelay(200);

            assertEquals(300, context.getTotalDelayMillis());
        }

        @Test
        @DisplayName("Start time is recorded")
        void startTimeIsRecorded() {
            RetryContext context = new RetryContext(3);
            assertNotNull(context.getStartTime());
        }
    }

    @Nested
    @DisplayName("Reset")
    class ResetTests {

        @Test
        @DisplayName("reset clears state")
        void reset_clearsState() {
            RetryContext context = new RetryContext(3);

            context.startAttempt();
            context.recordFailure(new RuntimeException("Error"));
            context.recordDelay(100);

            context.reset();

            assertEquals(0, context.getAttemptCount());
            assertNull(context.getLastError());
            assertNull(context.getLastErrorCategory());
            assertEquals(0, context.getTotalDelayMillis());
            assertTrue(context.hasAttemptsRemaining());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString provides useful information")
        void toString_providesUsefulInfo() {
            RetryContext context = new RetryContext("test-op", 5);
            context.startAttempt();
            context.recordFailure(new RuntimeException());

            String str = context.toString();

            assertTrue(str.contains("test-op"));
            assertTrue(str.contains("1/5"));
        }
    }
}

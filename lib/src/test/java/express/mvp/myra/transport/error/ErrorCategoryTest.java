package express.mvp.myra.transport.error;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ErrorCategory}.
 */
@DisplayName("ErrorCategory")
class ErrorCategoryTest {

    @Nested
    @DisplayName("Retryable categories")
    class RetryableTests {

        @Test
        @DisplayName("TRANSIENT is retryable")
        void transientIsRetryable() {
            assertTrue(ErrorCategory.TRANSIENT.isRetryable());
        }

        @Test
        @DisplayName("NETWORK is retryable")
        void networkIsRetryable() {
            assertTrue(ErrorCategory.NETWORK.isRetryable());
        }

        @Test
        @DisplayName("RESOURCE is retryable")
        void resourceIsRetryable() {
            assertTrue(ErrorCategory.RESOURCE.isRetryable());
        }

        @Test
        @DisplayName("UNKNOWN is retryable (conservative)")
        void unknownIsRetryable() {
            assertTrue(ErrorCategory.UNKNOWN.isRetryable());
        }

        @Test
        @DisplayName("PROTOCOL is not retryable")
        void protocolNotRetryable() {
            assertFalse(ErrorCategory.PROTOCOL.isRetryable());
        }

        @Test
        @DisplayName("FATAL is not retryable")
        void fatalNotRetryable() {
            assertFalse(ErrorCategory.FATAL.isRetryable());
        }
    }

    @Nested
    @DisplayName("Reconnect requirement")
    class ReconnectTests {

        @Test
        @DisplayName("NETWORK requires reconnect")
        void networkRequiresReconnect() {
            assertTrue(ErrorCategory.NETWORK.requiresReconnect());
        }

        @Test
        @DisplayName("FATAL requires reconnect")
        void fatalRequiresReconnect() {
            assertTrue(ErrorCategory.FATAL.requiresReconnect());
        }

        @Test
        @DisplayName("TRANSIENT does not require reconnect")
        void transientNoReconnect() {
            assertFalse(ErrorCategory.TRANSIENT.requiresReconnect());
        }

        @Test
        @DisplayName("RESOURCE does not require reconnect")
        void resourceNoReconnect() {
            assertFalse(ErrorCategory.RESOURCE.requiresReconnect());
        }
    }

    @Nested
    @DisplayName("Fatal detection")
    class FatalTests {

        @Test
        @DisplayName("Only FATAL is fatal")
        void onlyFatalIsFatal() {
            assertFalse(ErrorCategory.TRANSIENT.isFatal());
            assertFalse(ErrorCategory.NETWORK.isFatal());
            assertFalse(ErrorCategory.PROTOCOL.isFatal());
            assertFalse(ErrorCategory.RESOURCE.isFatal());
            assertFalse(ErrorCategory.UNKNOWN.isFatal());
            assertTrue(ErrorCategory.FATAL.isFatal());
        }
    }

    @Nested
    @DisplayName("Description")
    class DescriptionTests {

        @Test
        @DisplayName("All categories have descriptions")
        void allHaveDescriptions() {
            for (ErrorCategory cat : ErrorCategory.values()) {
                assertNotNull(cat.description());
                assertFalse(cat.description().isEmpty());
            }
        }

        @Test
        @DisplayName("toString includes description")
        void toStringIncludesDescription() {
            for (ErrorCategory cat : ErrorCategory.values()) {
                String str = cat.toString();
                assertTrue(str.contains(cat.name()));
                assertTrue(str.contains(cat.description()));
            }
        }
    }
}

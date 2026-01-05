package express.mvp.myra.transport.error;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ErrorClassifier}. */
@DisplayName("ErrorClassifier")
class ErrorClassifierTest {

    @AfterEach
    void cleanup() {
        ErrorClassifier.clearCustomClassifiers();
    }

    @Nested
    @DisplayName("Network errors")
    class NetworkErrorTests {

        @Test
        @DisplayName("ConnectException is NETWORK")
        void connectException_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new ConnectException("Connection refused")));
        }

        @Test
        @DisplayName("UnknownHostException is NETWORK")
        void unknownHostException_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new UnknownHostException("host.invalid")));
        }

        @Test
        @DisplayName("NoRouteToHostException is NETWORK")
        void noRouteToHostException_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new NoRouteToHostException("unreachable")));
        }

        @Test
        @DisplayName("ClosedChannelException is NETWORK")
        void closedChannelException_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK, ErrorClassifier.classify(new ClosedChannelException()));
        }

        @Test
        @DisplayName("SocketException with connection reset is NETWORK")
        void socketException_connectionReset_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new SocketException("Connection reset")));
        }

        @Test
        @DisplayName("SocketException with broken pipe is NETWORK")
        void socketException_brokenPipe_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new SocketException("Broken pipe")));
        }

        @Test
        @DisplayName("IOException with connection message is NETWORK")
        void ioException_connectionMessage_isNetwork() {
            assertEquals(
                    ErrorCategory.NETWORK,
                    ErrorClassifier.classify(new IOException("Connection lost")));
        }
    }

    @Nested
    @DisplayName("Transient errors")
    class TransientErrorTests {

        @Test
        @DisplayName("TimeoutException is TRANSIENT")
        void timeoutException_isTransient() {
            assertEquals(
                    ErrorCategory.TRANSIENT,
                    ErrorClassifier.classify(new TimeoutException("Operation timed out")));
        }

        @Test
        @DisplayName("SocketTimeoutException is TRANSIENT")
        void socketTimeoutException_isTransient() {
            assertEquals(
                    ErrorCategory.TRANSIENT,
                    ErrorClassifier.classify(new SocketTimeoutException("Read timed out")));
        }

        @Test
        @DisplayName("InterruptedException is TRANSIENT")
        void interruptedException_isTransient() {
            assertEquals(
                    ErrorCategory.TRANSIENT, ErrorClassifier.classify(new InterruptedException()));
        }

        @Test
        @DisplayName("Exception with 'timeout' in message is TRANSIENT")
        void exceptionWithTimeout_isTransient() {
            assertEquals(
                    ErrorCategory.TRANSIENT,
                    ErrorClassifier.classify(new RuntimeException("Operation timeout exceeded")));
        }

        @Test
        @DisplayName("Exception with 'busy' in message is TRANSIENT")
        void exceptionWithBusy_isTransient() {
            assertEquals(
                    ErrorCategory.TRANSIENT,
                    ErrorClassifier.classify(new RuntimeException("Server is busy")));
        }
    }

    @Nested
    @DisplayName("Resource errors")
    class ResourceErrorTests {

        @Test
        @DisplayName("RejectedExecutionException is RESOURCE")
        void rejectedExecutionException_isResource() {
            assertEquals(
                    ErrorCategory.RESOURCE,
                    ErrorClassifier.classify(new RejectedExecutionException("Queue full")));
        }

        @Test
        @DisplayName("BufferOverflowException is RESOURCE")
        void bufferOverflowException_isResource() {
            assertEquals(
                    ErrorCategory.RESOURCE,
                    ErrorClassifier.classify(new BufferOverflowException()));
        }

        @Test
        @DisplayName("Exception with 'too many open files' is RESOURCE")
        void tooManyOpenFiles_isResource() {
            assertEquals(
                    ErrorCategory.RESOURCE,
                    ErrorClassifier.classify(new IOException("Too many open files")));
        }
    }

    @Nested
    @DisplayName("Fatal errors")
    class FatalErrorTests {

        @Test
        @DisplayName("StackOverflowError is FATAL")
        void stackOverflowError_isFatal() {
            assertEquals(ErrorCategory.FATAL, ErrorClassifier.classify(new StackOverflowError()));
        }

        @Test
        @DisplayName("OutOfMemoryError is FATAL (VirtualMachineError)")
        void outOfMemoryError_isFatal() {
            assertEquals(
                    ErrorCategory.FATAL,
                    ErrorClassifier.classify(new OutOfMemoryError("Java heap space")));
        }

        @Test
        @DisplayName("LinkageError is FATAL")
        void linkageError_isFatal() {
            assertEquals(
                    ErrorCategory.FATAL,
                    ErrorClassifier.classify(new NoClassDefFoundError("SomeClass")));
        }

        @Test
        @DisplayName("SecurityException is FATAL")
        void securityException_isFatal() {
            assertEquals(
                    ErrorCategory.FATAL,
                    ErrorClassifier.classify(new SecurityException("Access denied")));
        }
    }

    @Nested
    @DisplayName("Protocol errors")
    class ProtocolErrorTests {

        @Test
        @DisplayName("IllegalArgumentException with 'frame' is PROTOCOL")
        void illegalArgumentException_frame_isProtocol() {
            assertEquals(
                    ErrorCategory.PROTOCOL,
                    ErrorClassifier.classify(new IllegalArgumentException("Invalid frame length")));
        }

        @Test
        @DisplayName("IllegalArgumentException with 'malformed' is PROTOCOL")
        void illegalArgumentException_malformed_isProtocol() {
            assertEquals(
                    ErrorCategory.PROTOCOL,
                    ErrorClassifier.classify(new IllegalArgumentException("Malformed request")));
        }

        @Test
        @DisplayName("Exception with 'invalid message' is PROTOCOL")
        void exception_invalidMessage_isProtocol() {
            assertEquals(
                    ErrorCategory.PROTOCOL,
                    ErrorClassifier.classify(
                            new RuntimeException("Received invalid message format")));
        }
    }

    @Nested
    @DisplayName("Cause chain analysis")
    class CauseChainTests {

        @Test
        @DisplayName("Classifies by cause when direct classification fails")
        void classifiesByCause() {
            ConnectException networkCause = new ConnectException("Connection refused");
            RuntimeException wrapper = new RuntimeException("Operation failed", networkCause);

            assertEquals(ErrorCategory.NETWORK, ErrorClassifier.classify(wrapper));
        }

        @Test
        @DisplayName("Deep cause chain is analyzed")
        void deepCauseChain() {
            SocketTimeoutException root = new SocketTimeoutException("Read timeout");
            IOException middle = new IOException("I/O error", root);
            RuntimeException outer = new RuntimeException("Wrapper", middle);

            assertEquals(ErrorCategory.TRANSIENT, ErrorClassifier.classify(outer));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("null returns UNKNOWN")
        void nullReturnsUnknown() {
            assertEquals(ErrorCategory.UNKNOWN, ErrorClassifier.classify(null));
        }

        @Test
        @DisplayName("Plain RuntimeException returns UNKNOWN")
        void plainRuntimeException_returnsUnknown() {
            assertEquals(
                    ErrorCategory.UNKNOWN,
                    ErrorClassifier.classify(new RuntimeException("Something happened")));
        }

        @Test
        @DisplayName("Exception with null message handles gracefully")
        void exceptionWithNullMessage() {
            RuntimeException e = new RuntimeException((String) null);
            // Should not throw NPE
            assertNotNull(ErrorClassifier.classify(e));
        }
    }

    @Nested
    @DisplayName("Custom classifiers")
    class CustomClassifierTests {

        @Test
        @DisplayName("Custom classifier can be registered")
        void customClassifierCanBeRegistered() {
            ErrorClassifier.registerClassifier(CustomException.class, e -> ErrorCategory.TRANSIENT);

            assertEquals(ErrorCategory.TRANSIENT, ErrorClassifier.classify(new CustomException()));
        }

        @Test
        @DisplayName("Custom classifier can be removed")
        void customClassifierCanBeRemoved() {
            ErrorClassifier.registerClassifier(CustomException.class, e -> ErrorCategory.TRANSIENT);
            ErrorClassifier.removeClassifier(CustomException.class);
            assertEquals(ErrorCategory.UNKNOWN, ErrorClassifier.classify(new CustomException()));
        }

        @Test
        @DisplayName("clearCustomClassifiers removes all")
        void clearRemovesAll() {
            ErrorClassifier.registerClassifier(CustomException.class, e -> ErrorCategory.TRANSIENT);
            ErrorClassifier.clearCustomClassifiers();
            assertEquals(ErrorCategory.UNKNOWN, ErrorClassifier.classify(new CustomException()));
        }

        private static class CustomException extends RuntimeException {}
    }

    @Nested
    @DisplayName("Error description")
    class ErrorDescriptionTests {

        @Test
        @DisplayName("describeError provides formatted output")
        void describeError_providesFormattedOutput() {
            ConnectException e = new ConnectException("Connection refused");
            String description = ErrorClassifier.describeError(e);

            assertTrue(description.contains("NETWORK"));
            assertTrue(description.contains("ConnectException"));
            assertTrue(description.contains("Connection refused"));
        }

        @Test
        @DisplayName("describeError handles null")
        void describeError_handlesNull() {
            String description = ErrorClassifier.describeError(null);
            assertNotNull(description);
            assertTrue(description.contains("null"));
        }

        @Test
        @DisplayName("describeError includes cause when present")
        void describeError_includesCause() {
            IOException root = new IOException("Root cause");
            RuntimeException wrapper = new RuntimeException("Wrapper", root);
            String description = ErrorClassifier.describeError(wrapper);

            assertTrue(description.contains("Cause:"));
            assertTrue(description.contains("IOException"));
        }
    }
}

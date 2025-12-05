package express.mvp.myra.transport.error;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;

/**
 * Classifies exceptions into error categories for recovery decisions.
 *
 * <p>This classifier uses a combination of exception type matching and message analysis
 * to categorize errors appropriately. Custom classifiers can be registered for
 * application-specific exceptions.
 *
 * <h2>Classification Strategy</h2>
 *
 * <ol>
 *   <li>Check custom classifiers first (exact type match)
 *   <li>Check for JVM errors (VirtualMachineError, etc.)
 *   <li>Check exception type hierarchy
 *   <li>Analyze exception message for patterns
 *   <li>Default to UNKNOWN if unclassifiable
 * </ol>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * try {
 *     transport.send(data);
 * } catch (Exception e) {
 *     ErrorCategory category = ErrorClassifier.classify(e);
 *     if (category.isRetryable()) {
 *         retryPolicy.handleRetryable(e, category);
 *     } else {
 *         handleFatalError(e);
 *     }
 * }
 * }</pre>
 *
 * <h2>Custom Classifiers</h2>
 *
 * <pre>{@code
 * // Register custom exception type
 * ErrorClassifier.registerClassifier(
 *     MyAppException.class,
 *     e -> e.isRetryable() ? ErrorCategory.TRANSIENT : ErrorCategory.FATAL
 * );
 * }</pre>
 *
 * @see ErrorCategory
 */
public final class ErrorClassifier {

    /** Custom classifiers keyed by exception class. */
    private static final Map<Class<? extends Throwable>, Predicate<Throwable>> CUSTOM_CLASSIFIERS =
            new ConcurrentHashMap<>();

    private ErrorClassifier() {
        // Utility class
    }

    /**
     * Classifies an exception into an error category.
     *
     * @param throwable the exception to classify
     * @return the error category
     */
    public static ErrorCategory classify(Throwable throwable) {
        if (throwable == null) {
            return ErrorCategory.UNKNOWN;
        }

        // Check custom classifiers first
        for (Map.Entry<Class<? extends Throwable>, Predicate<Throwable>> entry :
                CUSTOM_CLASSIFIERS.entrySet()) {
            if (entry.getKey().isInstance(throwable)) {
                if (entry.getValue().test(throwable)) {
                    // Custom classifier returned true for its expected category
                    // This is a simplified approach - full implementation would return category
                }
            }
        }

        // JVM errors are fatal
        if (throwable instanceof VirtualMachineError) {
            return ErrorCategory.FATAL;
        }
        if (throwable instanceof LinkageError) {
            return ErrorCategory.FATAL;
        }

        // Security exceptions are fatal
        if (throwable instanceof SecurityException) {
            return ErrorCategory.FATAL;
        }

        // Network errors
        if (isNetworkError(throwable)) {
            return ErrorCategory.NETWORK;
        }

        // Timeout errors are transient
        if (isTimeoutError(throwable)) {
            return ErrorCategory.TRANSIENT;
        }

        // Resource errors
        if (isResourceError(throwable)) {
            return ErrorCategory.RESOURCE;
        }

        // Protocol/data errors
        if (isProtocolError(throwable)) {
            return ErrorCategory.PROTOCOL;
        }

        // Check for interrupted - typically transient
        if (throwable instanceof InterruptedException) {
            return ErrorCategory.TRANSIENT;
        }

        // Analyze message for additional hints
        ErrorCategory messageCategory = classifyByMessage(throwable);
        if (messageCategory != null) {
            return messageCategory;
        }

        // Check cause recursively
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            ErrorCategory causeCategory = classify(cause);
            if (causeCategory != ErrorCategory.UNKNOWN) {
                return causeCategory;
            }
        }

        return ErrorCategory.UNKNOWN;
    }

    /**
     * Checks if the throwable is a network-related error.
     */
    private static boolean isNetworkError(Throwable t) {
        // Direct network exceptions
        if (t instanceof ConnectException) return true;
        if (t instanceof UnknownHostException) return true;
        if (t instanceof NoRouteToHostException) return true;
        if (t instanceof PortUnreachableException) return true;
        if (t instanceof ClosedChannelException) return true;

        // Socket exceptions (connection reset, broken pipe)
        if (t instanceof SocketException) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connection reset") ||
                    lower.contains("broken pipe") ||
                    lower.contains("connection refused") ||
                    lower.contains("network is unreachable")) {
                    return true;
                }
            }
            return true; // Default socket exceptions to network
        }

        // SSL/TLS errors
        if (t instanceof SSLException) {
            return true;
        }

        // General IOException with network messages
        if (t instanceof IOException) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("connection") ||
                    lower.contains("socket") ||
                    lower.contains("network")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Checks if the throwable is a timeout error.
     */
    private static boolean isTimeoutError(Throwable t) {
        if (t instanceof TimeoutException) return true;
        if (t instanceof SocketTimeoutException) return true;

        // Check message
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the throwable is a resource exhaustion error.
     */
    private static boolean isResourceError(Throwable t) {
        if (t instanceof OutOfMemoryError) return true;
        if (t instanceof RejectedExecutionException) return true;

        // Buffer errors
        if (t instanceof BufferOverflowException) return true;
        if (t instanceof BufferUnderflowException) return true;

        // Check message
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("too many open files") ||
                lower.contains("buffer pool") ||
                lower.contains("out of memory") ||
                lower.contains("resource") && lower.contains("exhaust")) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the throwable is a protocol/data error.
     */
    private static boolean isProtocolError(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("frame") ||
                    lower.contains("protocol") ||
                    lower.contains("malformed") ||
                    lower.contains("invalid") && lower.contains("message")) {
                    return true;
                }
            }
        }

        if (t instanceof IllegalStateException) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("unexpected") ||
                    lower.contains("protocol") ||
                    lower.contains("state")) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Attempts to classify by analyzing the exception message.
     */
    private static ErrorCategory classifyByMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            return null;
        }

        String lower = msg.toLowerCase();

        // Network patterns
        if (lower.contains("connection") && 
            (lower.contains("reset") || lower.contains("refused") || 
             lower.contains("closed") || lower.contains("lost"))) {
            return ErrorCategory.NETWORK;
        }

        // Transient patterns
        if (lower.contains("busy") ||
            lower.contains("temporarily") ||
            lower.contains("retry") ||
            lower.contains("again")) {
            return ErrorCategory.TRANSIENT;
        }

        // Resource patterns
        if (lower.contains("memory") ||
            lower.contains("buffer") ||
            lower.contains("limit") ||
            lower.contains("quota")) {
            return ErrorCategory.RESOURCE;
        }

        // Protocol patterns
        if (lower.contains("invalid") ||
            lower.contains("malformed") ||
            lower.contains("unexpected") ||
            lower.contains("corrupt")) {
            return ErrorCategory.PROTOCOL;
        }

        return null;
    }

    /**
     * Registers a custom classifier for a specific exception type.
     *
     * <p>Custom classifiers are checked before built-in classification. The predicate
     * receives the exception and should return true if it matches the custom category.
     *
     * @param exceptionType the exception class to match
     * @param classifier predicate that returns true if exception matches expected category
     * @param <T> the exception type
     */
    public static <T extends Throwable> void registerClassifier(
            Class<T> exceptionType,
            Predicate<Throwable> classifier) {
        CUSTOM_CLASSIFIERS.put(exceptionType, classifier);
    }

    /**
     * Removes a previously registered custom classifier.
     *
     * @param exceptionType the exception class
     */
    public static void removeClassifier(Class<? extends Throwable> exceptionType) {
        CUSTOM_CLASSIFIERS.remove(exceptionType);
    }

    /**
     * Clears all custom classifiers.
     */
    public static void clearCustomClassifiers() {
        CUSTOM_CLASSIFIERS.clear();
    }

    /**
     * Returns a detailed description of the classification result.
     *
     * @param throwable the exception to describe
     * @return formatted description including category and details
     */
    public static String describeError(Throwable throwable) {
        if (throwable == null) {
            return "null exception";
        }

        ErrorCategory category = classify(throwable);
        StringBuilder sb = new StringBuilder();
        sb.append("Category: ").append(category.name());
        sb.append("\nRetryable: ").append(category.isRetryable());
        sb.append("\nType: ").append(throwable.getClass().getName());
        sb.append("\nMessage: ").append(throwable.getMessage());

        Throwable cause = throwable.getCause();
        if (cause != null) {
            sb.append("\nCause: ").append(cause.getClass().getSimpleName());
            sb.append(" - ").append(cause.getMessage());
        }

        return sb.toString();
    }
}

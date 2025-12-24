package express.mvp.myra.transport.iouring;

import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.EAGAIN;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.EALREADY;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.ECONNREFUSED;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.ECONNRESET;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.EINPROGRESS;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.EPIPE;
import static express.mvp.roray.ffm.utils.functions.ErrnoCapture.ETIMEDOUT;

import express.mvp.myra.transport.TransportException;

/**
 * Utility class for handling Linux errno codes in io_uring operations.
 *
 * <p>This class maps native Linux error codes to meaningful Java exceptions with actionable
 * recovery hints. It is essential for debugging io_uring operations where completion queue entries
 * (CQEs) return negative errno values on failure.
 *
 * <h2>Common Error Scenarios</h2>
 *
 * <table border="1">
 *   <caption>Errno codes and their meanings</caption>
 *   <tr><th>Errno</th><th>Name</th><th>Meaning</th><th>Recovery</th></tr>
 *   <tr><td>11</td><td>EAGAIN</td><td>Resource temporarily unavailable</td><td>Retry operation</td></tr>
 *   <tr><td>32</td><td>EPIPE</td><td>Broken pipe</td><td>Reconnect</td></tr>
 *   <tr><td>104</td><td>ECONNRESET</td><td>Connection reset by peer</td><td>Reconnect</td></tr>
 *   <tr><td>110</td><td>ETIMEDOUT</td><td>Connection timed out</td><td>Check network, retry</td></tr>
 *   <tr><td>111</td><td>ECONNREFUSED</td><td>Connection refused</td><td>Verify server running</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * int result = LibUring.cqeGetRes(cqe);
 * if (result < 0) {
 *     if (ErrnoHandler.isRetryable(result)) {
 *         // Retry the operation
 *     } else if (ErrnoHandler.isConnectionLost(result)) {
 *         // Reconnect
 *     } else {
 *         throw ErrnoHandler.fromErrno(result, "send");
 *     }
 * }
 * }</pre>
 *
 * @see LibUring#cqeGetRes(java.lang.foreign.MemorySegment)
 * @see TransportException
 */
public final class ErrnoHandler {

    private ErrnoHandler() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a {@link TransportException} from an errno code with context.
     *
     * <p>The exception message includes the operation name, error description, and actionable
     * recovery hints for common errors.
     *
     * @param errno the Linux errno value (may be negative, will be converted to absolute)
     * @param operation description of the failed operation (e.g., "send", "recv", "connect")
     * @return a TransportException with descriptive message and recovery hints
     */
    public static TransportException fromErrno(int errno, String operation) {
        int absErrno = Math.abs(errno);

        return switch (absErrno) {
            case EAGAIN ->
                    new TransportException(
                            operation
                                    + " failed: Resource temporarily unavailable (EAGAIN). "
                                    + "Hint: Retry the operation or increase queue size.");

            case EPIPE ->
                    new TransportException(
                            operation
                                    + " failed: Broken pipe (EPIPE). "
                                    + "Hint: Remote end closed connection. Reconnect required.");

            case ECONNRESET ->
                    new TransportException(
                            operation
                                    + " failed: Connection reset by peer (ECONNRESET). Hint: Remote"
                                    + " end forcefully closed connection. Reconnect required.");

            case ETIMEDOUT ->
                    new TransportException(
                            operation
                                    + " failed: Connection timed out (ETIMEDOUT). Hint: Network"
                                    + " issue or remote host unreachable. Check connectivity and"
                                    + " retry.");

            case ECONNREFUSED ->
                    new TransportException(
                            operation
                                    + " failed: Connection refused (ECONNREFUSED). Hint: No service"
                                    + " listening on target port. Verify server is running.");

            case EINPROGRESS ->
                    new TransportException(
                            operation
                                    + " failed: Operation already in progress (EINPROGRESS). "
                                    + "Hint: Wait for current operation to complete.");

            case EALREADY ->
                    new TransportException(
                            operation
                                    + " failed: Operation already in progress (EALREADY). "
                                    + "Hint: Connection attempt is still pending.");

            default ->
                    new TransportException(
                            operation
                                    + " failed: errno="
                                    + absErrno
                                    + ". "
                                    + "Hint: Check system error documentation for details.");
        };
    }

    /**
     * Checks if the errno indicates a retryable (transient) error.
     *
     * <p>Retryable errors are temporary conditions that may succeed on retry, such as resource
     * temporarily unavailable or operation in progress.
     *
     * @param errno the Linux errno value (may be negative)
     * @return {@code true} if the operation should be retried
     */
    public static boolean isRetryable(int errno) {
        int absErrno = Math.abs(errno);
        return absErrno == EAGAIN || absErrno == EINPROGRESS;
    }

    /**
     * Checks if the errno indicates the connection was lost.
     *
     * <p>Connection loss requires reconnection to recover. This includes broken pipe, connection
     * reset, and timeout errors.
     *
     * @param errno the Linux errno value (may be negative)
     * @return {@code true} if the connection was lost and reconnection is needed
     */
    public static boolean isConnectionLost(int errno) {
        int absErrno = Math.abs(errno);
        return absErrno == EPIPE || absErrno == ECONNRESET || absErrno == ETIMEDOUT;
    }

    /**
     * Checks if the errno indicates the connection was refused.
     *
     * <p>Connection refused means no service is listening on the target port. This typically
     * requires verifying the server is running before retrying.
     *
     * @param errno the Linux errno value (may be negative)
     * @return {@code true} if the connection was refused
     */
    public static boolean isConnectionRefused(int errno) {
        int absErrno = Math.abs(errno);
        return absErrno == ECONNREFUSED;
    }
}

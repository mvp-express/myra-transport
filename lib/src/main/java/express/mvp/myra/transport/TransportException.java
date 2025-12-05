package express.mvp.myra.transport;

/**
 * Unchecked exception thrown when transport operations fail.
 *
 * <p>This exception wraps I/O errors, connection failures, and protocol violations that occur
 * during transport operations. It extends {@link RuntimeException} to avoid cluttering method
 * signatures with checked exceptions.
 *
 * <h2>Common Causes</h2>
 *
 * <ul>
 *   <li>Connection failures (refused, timeout, reset)
 *   <li>I/O errors during send/receive operations
 *   <li>Buffer pool exhaustion
 *   <li>io_uring ring overflow or syscall failures
 *   <li>Invalid state transitions (e.g., send before connect)
 * </ul>
 *
 * <h2>Error Recovery</h2>
 *
 * <p>When catching this exception, examine the message and cause to determine the appropriate
 * recovery action. For io_uring backends, the message often includes errno codes and recovery hints
 * from {@link express.mvp.myra.transport.iouring.ErrnoHandler}.
 *
 * @see express.mvp.myra.transport.iouring.ErrnoHandler
 */
public class TransportException extends RuntimeException {

    /**
     * Constructs a new transport exception with the specified message.
     *
     * @param message the detail message describing the failure
     */
    public TransportException(String message) {
        super(message);
    }

    /**
     * Constructs a new transport exception with the specified message and cause.
     *
     * @param message the detail message describing the failure
     * @param cause the underlying cause of the failure
     */
    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new transport exception with the specified cause.
     *
     * @param cause the underlying cause of the failure
     */
    public TransportException(Throwable cause) {
        super(cause);
    }
}

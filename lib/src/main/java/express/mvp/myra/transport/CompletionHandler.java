package express.mvp.myra.transport;

/**
 * Callback interface for handling completion of asynchronous I/O operations.
 *
 * <p>This is the primary callback interface for receiving notifications when io_uring or NIO
 * operations complete. It follows a zero-allocation design pattern critical for high-frequency
 * trading (HFT) and low-latency applications.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Allocation-free:</b> Implementations must not allocate objects during callbacks to avoid
 *       GC pauses on the hot path
 *   <li><b>Token-based tracking:</b> The token correlates completions with their originating
 *       operations without object references
 *   <li><b>Unified result model:</b> Positive results indicate bytes transferred, negative results
 *       indicate Linux errno codes
 * </ul>
 *
 * <h2>Result Interpretation</h2>
 *
 * <table border="1">
 *   <caption>Result value meanings</caption>
 *   <tr><th>Result</th><th>Meaning</th></tr>
 *   <tr><td>{@code > 0}</td><td>Success: number of bytes transferred</td></tr>
 *   <tr><td>{@code 0}</td><td>Success with no data (e.g., connect completed)</td></tr>
 *   <tr><td>{@code -1}</td><td>EOF: peer closed connection</td></tr>
 *   <tr><td>{@code < -1}</td><td>Error: negated Linux errno (e.g., -104 = ECONNRESET)</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * CompletionHandler handler = (token, result) -> {
 *     if (result >= 0) {
 *         System.out.println("Operation " + token + " transferred " + result + " bytes");
 *     } else if (result == -1) {
 *         System.out.println("Connection closed");
 *     } else {
 *         System.err.println("Error: errno=" + (-result));
 *     }
 * };
 *
 * backend.poll(handler);
 * }</pre>
 *
 * @see express.mvp.myra.transport.iouring.IoUringBackend.ExtendedCompletionHandler
 * @see express.mvp.myra.transport.iouring.ErrnoHandler
 */
@FunctionalInterface
public interface CompletionHandler {

    /**
     * Called when an asynchronous I/O operation completes.
     *
     * <p><b>Thread Safety:</b> This method is called from the I/O polling thread. Implementations
     * must be thread-safe if the handler is shared across threads.
     *
     * <p><b>Performance:</b> This method is on the hot path. Avoid allocations, blocking calls, or
     * expensive computations.
     *
     * @param token the user-defined token passed when the operation was initiated; use this to
     *     correlate completions with pending operations
     * @param result the operation result:
     *     <ul>
     *       <li>Positive: bytes transferred
     *       <li>Zero: operation completed with no data
     *       <li>-1: EOF (peer closed connection)
     *       <li>Negative: Linux errno (use {@code -result} to get errno value)
     *     </ul>
     */
    void onComplete(long token, int result);
}

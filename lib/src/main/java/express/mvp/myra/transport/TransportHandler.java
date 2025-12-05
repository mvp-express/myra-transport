package express.mvp.myra.transport;

import java.lang.foreign.MemorySegment;

/**
 * Callback interface for handling transport lifecycle and I/O events.
 *
 * <p>This interface provides event-driven notifications for all transport activities including
 * connection establishment, data reception, send completion, and disconnection. It is the primary
 * way to interact with {@link Transport} instances.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 *   <li><b>Zero-allocation:</b> Callbacks receive raw {@link MemorySegment} references to avoid
 *       copying data. The segment is only valid during the callback.
 *   <li><b>Non-blocking:</b> Callbacks are invoked on the I/O thread. Blocking operations will
 *       stall all I/O processing.
 *   <li><b>Token-based tracking:</b> Send operations return tokens that correlate with completion
 *       callbacks.
 * </ul>
 *
 * <h2>Buffer Lifecycle</h2>
 *
 * <p>The {@code data} parameter in {@link #onDataReceived(MemorySegment)} is a view into the
 * transport's internal buffer. It is only valid for the duration of the callback. To preserve data
 * beyond the callback:
 *
 * <ul>
 *   <li>Copy the data to your own buffer: {@code data.copyFrom(myBuffer)}
 *   <li>Process the data inline within the callback
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * transport.start(new TransportHandler() {
 *     @Override
 *     public void onConnected(long token) {
 *         System.out.println("Connected!");
 *         // Start sending data or wait for incoming data
 *     }
 *
 *     @Override
 *     public void onDataReceived(MemorySegment data) {
 *         // Process received data - buffer only valid during this call
 *         byte[] bytes = data.toArray(ValueLayout.JAVA_BYTE);
 *         processMessage(bytes);
 *     }
 *
 *     @Override
 *     public void onSendComplete(long token) {
 *         // Send completed successfully
 *     }
 *
 *     // ... other callbacks
 * });
 * }</pre>
 *
 * @see Transport#start(TransportHandler)
 * @see TransportHandlerAdapter
 */
public interface TransportHandler {

    /**
     * Called when a connection is successfully established.
     *
     * <p>After this callback, the transport is ready for I/O operations. You can begin sending data
     * or the transport will automatically start receiving.
     *
     * @param token the token returned by {@link Transport#connect}
     */
    void onConnected(long token);

    /**
     * Called when a connection attempt fails.
     *
     * <p>The transport remains in a disconnected state. You may attempt to reconnect by calling
     * {@link Transport#connect} again.
     *
     * @param token the token returned by {@link Transport#connect}
     * @param cause the exception describing the failure (e.g., connection refused, timeout)
     */
    void onConnectionFailed(long token, Throwable cause);

    /**
     * Called when data is received from the remote peer.
     *
     * <p><b>Important:</b> The {@code data} segment is a view into the transport's internal buffer.
     * It is only valid for the duration of this callback. If you need to preserve the data, copy it
     * to your own buffer before returning.
     *
     * <p>The transport automatically posts the next receive operation after this callback returns.
     *
     * @param data a read-only view of the received data; valid only during this callback
     */
    void onDataReceived(MemorySegment data);

    /**
     * Called when a send operation completes successfully.
     *
     * <p>The buffer used for the send has been released back to the pool and may be reused for
     * subsequent operations.
     *
     * @param token the token returned by {@link Transport#send}
     */
    void onSendComplete(long token);

    /**
     * Called when a send operation fails.
     *
     * <p>Common causes include connection reset, broken pipe, or buffer allocation failure. The
     * associated buffer has been released.
     *
     * @param token the token returned by {@link Transport#send}
     * @param cause the exception describing the failure
     */
    void onSendFailed(long token, Throwable cause);

    /**
     * Called when the transport is closed.
     *
     * <p>This is called regardless of whether the close was initiated locally (via {@link
     * Transport#close()}) or remotely (peer disconnected). After this callback, the transport is no
     * longer usable.
     */
    void onClosed();
}

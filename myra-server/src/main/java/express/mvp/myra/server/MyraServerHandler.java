package express.mvp.myra.server;

import express.mvp.myra.transport.RegisteredBuffer;
import express.mvp.myra.transport.TransportBackend;

/**
 * Callback handler for MyraServer events.
 *
 * <p>Implementations of this interface receive notifications for the three main connection
 * lifecycle events: connect, data received, and disconnect. The handler is invoked on the server's
 * event loop thread, so implementations should be non-blocking to maintain high throughput.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>For each client connection, callbacks are invoked in this order:
 *
 * <ol>
 *   <li>{@link #onConnect(TransportBackend)} - called once when connection is accepted
 *   <li>{@link #onDataReceived(TransportBackend, RegisteredBuffer, int)} - called for each data
 *       chunk
 *   <li>{@link #onDisconnect(TransportBackend)} - called once when connection closes
 * </ol>
 *
 * <h2>Buffer Ownership</h2>
 *
 * <p>The buffer passed to {@link #onDataReceived} is owned by the server's buffer pool. The handler
 * may read from the buffer and optionally use it for a response, but must not hold references
 * beyond the callback scope unless explicitly acquired from the pool.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All callbacks for a given connection are invoked on the same thread (the server's event loop).
 * Callbacks for different connections may occur concurrently if the server uses multiple event
 * loops.
 *
 * <h2>Example Implementation</h2>
 *
 * <pre>{@code
 * public class EchoHandler implements MyraServerHandler {
 *     @Override
 *     public void onConnect(TransportBackend connection) {
 *         System.out.println("Client connected");
 *     }
 *
 *     @Override
 *     public void onDataReceived(TransportBackend connection,
 *                                 RegisteredBuffer buffer, int length) {
 *         // Echo the data back to the client
 *         connection.send(buffer, 0);
 *     }
 *
 *     @Override
 *     public void onDisconnect(TransportBackend connection) {
 *         System.out.println("Client disconnected");
 *     }
 * }
 * }</pre>
 *
 * @see MyraServer
 * @see TransportBackend
 * @see RegisteredBuffer
 */
public interface MyraServerHandler {

    /**
     * Called when a new client connection is accepted.
     *
     * <p>This callback provides an opportunity to initialize per-connection state or perform
     * handshake operations. The connection can be used to send data or configure read operations.
     *
     * @param connection the transport backend for the new connection; can be used to send data or
     *     configure I/O
     */
    void onConnect(TransportBackend connection);

    /**
     * Called when data is received from a client.
     *
     * <p>The buffer contains the received data from position 0 to {@code length}. The handler may:
     *
     * <ul>
     *   <li>Read the data directly from the buffer
     *   <li>Use the buffer to send a response (for zero-copy echo patterns)
     *   <li>Copy the data if it needs to be retained after the callback returns
     * </ul>
     *
     * <p><b>Important:</b> The buffer is returned to the pool after this callback unless it is
     * passed to {@link TransportBackend#send(RegisteredBuffer, long)}. Do not hold references to
     * the buffer after the callback returns.
     *
     * @param connection the transport backend for the connection
     * @param buffer the registered buffer containing received data
     * @param length the number of bytes received (valid data from 0 to length-1)
     */
    void onDataReceived(TransportBackend connection, RegisteredBuffer buffer, int length);

    /**
     * Called when a client connection is closed.
     *
     * <p>This callback is invoked when the connection is closed, either by the client, due to an
     * error, or when the server explicitly closes it. Use this to clean up any per-connection
     * resources.
     *
     * <p>After this callback, the connection should not be used for any I/O operations.
     *
     * @param connection the transport backend for the closed connection
     */
    void onDisconnect(TransportBackend connection);
}

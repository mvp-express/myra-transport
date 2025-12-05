package express.mvp.myra.transport;

import java.net.SocketAddress;

/**
 * Connection pool for managing TCP connections to multiple endpoints.
 *
 * <p>This interface provides connection pooling with automatic connection reuse, health checking,
 * and resource management. Connections are pooled per endpoint (host:port combination).
 *
 * <h2>Pooling Benefits</h2>
 *
 * <ul>
 *   <li><b>Reduced latency:</b> Reuse existing connections instead of TCP handshake
 *   <li><b>Resource efficiency:</b> Limit connections per host to prevent exhaustion
 *   <li><b>Health management:</b> Automatic removal of unhealthy connections
 *   <li><b>Thread safety:</b> Safe for concurrent acquire/release from multiple threads
 * </ul>
 *
 * <h2>Connection Lifecycle</h2>
 *
 * <ol>
 *   <li><b>Acquire:</b> Get a connection via {@link #acquire(SocketAddress, ConnectionHandler)}
 *       <ul>
 *         <li>Returns existing idle connection if available
 *         <li>Creates new connection if under limit
 *         <li>Fails if pool exhausted
 *       </ul>
 *   <li><b>Use:</b> Perform I/O operations on the connection
 *   <li><b>Release:</b> Return via {@link #release(Transport)}
 *       <ul>
 *         <li>Healthy connections return to idle pool
 *         <li>Unhealthy connections are closed
 *       </ul>
 * </ol>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ConnectionPool pool = TransportFactory.createPool(config);
 *
 * pool.acquire(new InetSocketAddress("api.example.com", 443),
 *     new ConnectionHandler() {
 *         @Override
 *         public void onConnectionAcquired(Transport conn, long token) {
 *             try {
 *                 // Use connection...
 *                 conn.send(request);
 *             } finally {
 *                 pool.release(conn);
 *             }
 *         }
 *
 *         @Override
 *         public void onConnectionFailed(Throwable cause, long token) {
 *             System.err.println("Connection failed: " + cause);
 *         }
 *     });
 * }</pre>
 *
 * @see ConnectionHandler
 * @see Transport
 * @see TransportFactory#createPool(TransportConfig)
 */
public interface ConnectionPool extends AutoCloseable {

    /**
     * Acquires a connection to the specified endpoint.
     *
     * <p>This method attempts to provide a connection in the following order:
     *
     * <ol>
     *   <li>Return an existing idle connection if one is available and healthy
     *   <li>Create a new connection if below the per-host limit
     *   <li>Fail with an error if the pool is exhausted
     * </ol>
     *
     * <p>The result is delivered asynchronously via the handler.
     *
     * @param endpoint the remote endpoint to connect to (e.g., {@code InetSocketAddress})
     * @param handler the handler to notify when connection is ready or failed
     * @return a token identifying this operation for correlation
     * @throws IllegalStateException if the pool is closed
     */
    long acquire(SocketAddress endpoint, ConnectionHandler handler);

    /**
     * Releases a connection back to the pool.
     *
     * <p>The connection is evaluated and either returned to the idle pool (if healthy) or closed
     * (if unhealthy). Released connections become available for future {@link #acquire} calls.
     *
     * <p>This method is idempotent; releasing an already-released connection has no effect.
     *
     * @param connection the connection to release (may be null, which is ignored)
     */
    void release(Transport connection);

    /**
     * Returns the total number of active (in-use) connections.
     *
     * <p>Active connections are those acquired but not yet released.
     *
     * @return the active connection count across all endpoints
     */
    int getActiveConnectionCount();

    /**
     * Returns the total number of idle (available) connections.
     *
     * <p>Idle connections are ready for immediate reuse.
     *
     * @return the idle connection count across all endpoints
     */
    int getIdleConnectionCount();

    /**
     * Closes all connections and shuts down the pool.
     *
     * <p>After closing:
     *
     * <ul>
     *   <li>All active and idle connections are closed
     *   <li>{@link #acquire} will fail immediately
     *   <li>The pool cannot be restarted
     * </ul>
     */
    @Override
    void close();
}

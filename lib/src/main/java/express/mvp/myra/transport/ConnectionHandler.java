package express.mvp.myra.transport;

/**
 * Callback interface for handling connection pool acquisition events.
 *
 * <p>This interface receives notifications when connections are acquired from or fail to be
 * acquired from a {@link ConnectionPool}. It enables asynchronous connection acquisition without
 * blocking the calling thread.
 *
 * <h2>Usage with Connection Pool</h2>
 *
 * <pre>{@code
 * ConnectionPool pool = TransportFactory.createPool(config);
 *
 * long token = pool.acquire(serverAddress, new ConnectionHandler() {
 *     @Override
 *     public void onConnectionAcquired(Transport transport, long token) {
 *         // Use the transport for I/O
 *         transport.send(data);
 *     }
 *
 *     @Override
 *     public void onConnectionFailed(Throwable cause, long token) {
 *         logger.error("Failed to acquire connection", cause);
 *     }
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Callbacks may be invoked from the connection pool's I/O thread or from the thread that called
 * {@link ConnectionPool#acquire}. Implementations must be thread-safe.
 *
 * @see ConnectionPool
 * @see Transport
 */
public interface ConnectionHandler {

    /**
     * Called when a connection is successfully acquired from the pool.
     *
     * <p>The transport is ready for I/O operations. When finished, call {@link
     * ConnectionPool#release(Transport)} to return it to the pool.
     *
     * @param transport the acquired transport instance, ready for use
     * @param token the operation token from the {@link ConnectionPool#acquire} call
     */
    void onConnectionAcquired(Transport transport, long token);

    /**
     * Called when connection acquisition fails.
     *
     * <p>Common failure causes include:
     *
     * <ul>
     *   <li>Connection pool exhausted (max connections per host reached)
     *   <li>Connection refused by remote host
     *   <li>Connection timeout
     *   <li>Network unreachable
     * </ul>
     *
     * @param cause the exception describing the failure reason
     * @param token the operation token from the {@link ConnectionPool#acquire} call
     */
    void onConnectionFailed(Throwable cause, long token);
}

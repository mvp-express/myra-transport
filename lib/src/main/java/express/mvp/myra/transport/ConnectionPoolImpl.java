package express.mvp.myra.transport;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe connection pool implementation.
 *
 * <p>This implementation manages per-endpoint connection pools with configurable limits. Each
 * endpoint (host:port) gets its own pool with independent connection tracking and semaphore-based
 * limiting.
 *
 * <h2>Implementation Details</h2>
 *
 * <ul>
 *   <li><b>Thread safety:</b> Uses {@link ConcurrentHashMap} and {@link Semaphore} for lock-free
 *       concurrent access
 *   <li><b>Per-endpoint isolation:</b> Each endpoint has independent connection tracking and limits
 *   <li><b>Lazy initialization:</b> Pool entries are created on first access
 *   <li><b>Connection reuse:</b> Idle connections are preferred over new connections
 * </ul>
 *
 * <h2>Connection Limits</h2>
 *
 * <p>The default maximum connections per host is 16. This can be tuned based on:
 *
 * <ul>
 *   <li>Expected concurrent requests to each host
 *   <li>Server connection limits
 *   <li>Available file descriptors
 * </ul>
 *
 * @see ConnectionPool
 * @see TransportFactory#createPool(TransportConfig)
 */
public final class ConnectionPoolImpl implements ConnectionPool {

    /** Per-endpoint pool entries. Keys are endpoint addresses (typically InetSocketAddress). */
    private final Map<SocketAddress, PoolEntry> pools = new ConcurrentHashMap<>();

    /** Transport configuration used for creating new connections. */
    private final TransportConfig config;

    /** Maximum connections allowed per endpoint. */
    private final int maxConnectionsPerHost;

    /** Generator for unique operation tokens. */
    private final AtomicLong tokenGenerator = new AtomicLong(0);

    /** Flag indicating whether the pool has been closed. */
    private volatile boolean closed = false;

    /**
     * Creates a new connection pool with the specified configuration.
     *
     * @param config the transport configuration for creating connections
     */
    public ConnectionPoolImpl(TransportConfig config) {
        this.config = config;
        this.maxConnectionsPerHost = 16; // Default max connections per host
    }

    @Override
    public long acquire(SocketAddress endpoint, ConnectionHandler handler) {
        long token = tokenGenerator.incrementAndGet();

        if (closed) {
            handler.onConnectionFailed(
                    new IllegalStateException("Connection pool is closed"), token);
            return token;
        }

        // Get or create pool entry for this endpoint
        PoolEntry entry =
                pools.computeIfAbsent(
                        endpoint, addr -> new PoolEntry(addr, maxConnectionsPerHost, config));

        entry.acquire(token, handler);
        return token;
    }

    @Override
    public void release(Transport connection) {
        if (connection == null) {
            return;
        }

        SocketAddress endpoint = connection.getRemoteAddress();
        if (endpoint == null) {
            connection.close();
            return;
        }

        PoolEntry entry = pools.get(endpoint);
        if (entry != null) {
            entry.release(connection);
        } else {
            // Connection doesn't belong to any pool entry, close it
            connection.close();
        }
    }

    @Override
    public int getActiveConnectionCount() {
        return pools.values().stream().mapToInt(PoolEntry::getActiveCount).sum();
    }

    @Override
    public int getIdleConnectionCount() {
        return pools.values().stream().mapToInt(PoolEntry::getIdleCount).sum();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        // Close all pool entries
        for (PoolEntry entry : pools.values()) {
            entry.close();
        }

        pools.clear();
    }

    /**
     * Pool entry for a specific endpoint.
     *
     * <p>Manages connections to a single host:port combination with semaphore-based connection
     * limiting and idle connection tracking.
     */
    private static final class PoolEntry {
        /** The endpoint this pool entry manages. */
        private final SocketAddress endpoint;

        /** Semaphore limiting concurrent connections. */
        private final Semaphore permits;

        /** Configuration for creating new connections. */
        private final TransportConfig config;

        /** Map of connections to their in-use status (true = active, false = idle). */
        private final Map<Transport, Boolean> connections = new ConcurrentHashMap<>();

        /**
         * Creates a new pool entry for the specified endpoint.
         *
         * @param endpoint the endpoint address
         * @param maxConnections maximum concurrent connections allowed
         * @param config transport configuration
         */
        PoolEntry(SocketAddress endpoint, int maxConnections, TransportConfig config) {
            this.endpoint = endpoint;
            this.permits = new Semaphore(maxConnections);
            this.config = config;
        }

        /**
         * Acquires a connection, either from idle pool or by creating new.
         *
         * @param token operation token for callback correlation
         * @param handler callback handler for result notification
         */
        void acquire(long token, ConnectionHandler handler) {
            // First, try to find an idle connection
            for (Map.Entry<Transport, Boolean> entry : connections.entrySet()) {
                Transport transport = entry.getKey();
                Boolean inUse = entry.getValue();

                if (!inUse && transport.isConnected()) {
                    // Try to atomically mark as in-use
                    if (connections.replace(transport, false, true)) {
                        handler.onConnectionAcquired(transport, token);
                        return;
                    }
                }
            }

            // No idle connection available, try to acquire permit for new connection
            if (!permits.tryAcquire()) {
                handler.onConnectionFailed(
                        new IllegalStateException("Connection pool exhausted for " + endpoint),
                        token);
                return;
            }

            // Create new connection
            try {
                RegisteredBufferPool bufferPool =
                        new RegisteredBufferPoolImpl(
                                config.registeredBuffersConfig().numBuffers(),
                                config.registeredBuffersConfig().bufferSize());

                TransportBackend backend = createBackend(config);
                backend.initialize(config);
                backend.registerBufferPool(bufferPool);
                Transport transport =
                        new TcpTransport(backend, bufferPool, endpoint, config.cpuAffinity());

                // Start transport with connect handler
                transport.start(
                        new TransportHandlerAdapter() {
                            @Override
                            public void onConnected(long connToken) {
                                connections.put(transport, true);
                                handler.onConnectionAcquired(transport, token);
                            }

                            @Override
                            public void onConnectionFailed(long connToken, Throwable cause) {
                                permits.release();
                                handler.onConnectionFailed(
                                        new TransportException(
                                                "Failed to connect to " + endpoint, cause),
                                        token);
                            }
                        });

                transport.connect(endpoint);
            } catch (Exception e) {
                permits.release();
                handler.onConnectionFailed(e, token);
            }
        }

        /**
         * Releases a connection back to the pool.
         *
         * @param connection the connection to release
         */
        void release(Transport connection) {
            Boolean inUse = connections.get(connection);
            if (inUse != null && inUse) {
                if (connection.isConnected()) {
                    // Healthy connection - mark as idle for reuse
                    connections.put(connection, false);
                } else {
                    // Unhealthy connection - remove and close
                    connections.remove(connection);
                    permits.release();
                    connection.close();
                }
            }
        }

        /**
         * Returns the count of active (in-use) connections.
         *
         * @return active connection count
         */
        int getActiveCount() {
            return (int) connections.values().stream().filter(inUse -> inUse).count();
        }

        /**
         * Returns the count of idle (available) connections.
         *
         * @return idle connection count
         */
        int getIdleCount() {
            return (int) connections.values().stream().filter(inUse -> !inUse).count();
        }

        /** Closes all connections managed by this pool entry. */
        void close() {
            for (Transport transport : connections.keySet()) {
                try {
                    transport.close();
                } catch (Exception e) {
                    // Ignore errors during shutdown
                }
            }
            connections.clear();
        }

        /**
         * Creates a backend based on configuration with fallback.
         *
         * @param config transport configuration
         * @return a new backend instance
         */
        private static TransportBackend createBackend(TransportConfig config) {
            switch (config.backendType()) {
                case NIO:
                    return new express.mvp.myra.transport.nio.NioBackend();
                case IO_URING:
                    try {
                        return new express.mvp.myra.transport.iouring.IoUringBackend();
                    } catch (UnsatisfiedLinkError e) {
                        // Fall back to NIO if io_uring not available
                        return new express.mvp.myra.transport.nio.NioBackend();
                    }
                default:
                    throw new IllegalArgumentException(
                            "Unsupported backend: " + config.backendType());
            }
        }
    }
}

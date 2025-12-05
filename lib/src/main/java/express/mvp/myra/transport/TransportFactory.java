package express.mvp.myra.transport;

import java.net.SocketAddress;

/**
 * Factory for creating transport instances and related components.
 *
 * <p>This factory abstracts the construction of transports, backends, and buffer pools, handling
 * backend selection and fallback logic automatically.
 *
 * <h2>Backend Selection</h2>
 *
 * <p>The factory attempts to create the requested backend type. If unavailable, it falls back
 * gracefully:
 *
 * <ul>
 *   <li><b>IO_URING:</b> Preferred on Linux 5.1+. Falls back to NIO if liburing is not available.
 *   <li><b>NIO:</b> Always available as the baseline implementation.
 *   <li><b>XDP/DPDK:</b> Experimental, not yet implemented.
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * TransportConfig config = TransportConfig.builder()
 *     .backendType(BackendType.IO_URING)
 *     .registeredBuffers(RegisteredBuffersConfig.builder()
 *         .numBuffers(256)
 *         .bufferSize(65536)
 *         .build())
 *     .build();
 *
 * InetSocketAddress remote = new InetSocketAddress("example.com", 8080);
 * try (Transport transport = TransportFactory.create(config, remote)) {
 *     transport.start(handler);
 *     // Use transport...
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This factory is thread-safe. Multiple threads can create transports concurrently.
 *
 * @see Transport
 * @see TransportConfig
 * @see TransportBackend
 */
public final class TransportFactory {

    /** Private constructor to prevent instantiation. All methods are static. */
    private TransportFactory() {
        // Utility class
    }

    /**
     * Creates a new transport with the given configuration.
     *
     * <p>This method creates and initializes all required components:
     *
     * <ol>
     *   <li>Creates a buffer pool with the configured size
     *   <li>Creates the I/O backend (io_uring or NIO)
     *   <li>Initializes the backend with configuration
     *   <li>Registers the buffer pool with the backend
     *   <li>Creates the TCP transport wrapper
     * </ol>
     *
     * @param config the transport configuration
     * @param remoteAddress the remote endpoint to connect to
     * @return a new transport instance ready for use
     * @throws TransportException if initialization fails
     * @throws IllegalArgumentException if registered buffers are disabled in config
     */
    public static Transport create(TransportConfig config, SocketAddress remoteAddress) {
        RegisteredBufferPool bufferPool = createBufferPool(config);
        TransportBackend backend = createBackend(config);
        backend.initialize(config);
        backend.registerBufferPool(bufferPool);

        return new TcpTransport(backend, bufferPool, remoteAddress, config.cpuAffinity());
    }

    /**
     * Creates a connection pool for managing multiple connections.
     *
     * @param config the transport configuration
     * @return a new connection pool instance
     */
    public static ConnectionPool createPool(TransportConfig config) {
        return new ConnectionPoolImpl(config);
    }

    /**
     * Creates a registered buffer pool with the configured parameters.
     *
     * <p>The buffer pool allocates off-heap memory and prepares it for registration with the I/O
     * backend.
     *
     * @param config the transport configuration containing buffer settings
     * @return a new buffer pool
     * @throws IllegalArgumentException if registered buffers are disabled
     */
    public static RegisteredBufferPool createBufferPool(TransportConfig config) {
        TransportConfig.RegisteredBuffersConfig bufferConfig = config.registeredBuffersConfig();

        if (!bufferConfig.enabled()) {
            throw new IllegalArgumentException("Registered buffers must be enabled");
        }

        return new RegisteredBufferPoolImpl(bufferConfig.numBuffers(), bufferConfig.bufferSize());
    }

    /**
     * Creates an I/O backend based on the configuration.
     *
     * <p>This method handles backend selection with automatic fallback:
     *
     * <ul>
     *   <li><b>IO_URING:</b> Attempts to load io_uring backend. If liburing is not installed or
     *       linked, falls back to NIO with a warning.
     *   <li><b>NIO:</b> Uses standard Java NIO (always available).
     *   <li><b>XDP/DPDK:</b> Throws UnsupportedOperationException (not yet implemented).
     * </ul>
     *
     * @param config the transport configuration
     * @return a new backend instance (not yet initialized)
     * @throws UnsupportedOperationException if XDP or DPDK is requested
     * @throws IllegalArgumentException if backend type is unknown
     */
    public static TransportBackend createBackend(TransportConfig config) {
        switch (config.backendType()) {
            case NIO:
                return new express.mvp.myra.transport.nio.NioBackend();

            case IO_URING:
                try {
                    return new express.mvp.myra.transport.iouring.IoUringBackend();
                } catch (UnsatisfiedLinkError e) {
                    // liburing not available, fall back to NIO
                    System.err.println(
                            "io_uring not available, falling back to NIO: " + e.getMessage());
                    return new express.mvp.myra.transport.nio.NioBackend();
                }

            case XDP:
            case DPDK:
                throw new UnsupportedOperationException(
                        config.backendType() + " backend not yet implemented");

            default:
                throw new IllegalArgumentException("Unknown backend type: " + config.backendType());
        }
    }
}

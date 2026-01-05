/**
 * Core transport API for building low-latency networking clients and services.
 *
 * <p>This package defines the main abstractions for configuring and operating transports, along
 * with buffer pool and handler contracts used by higher-level runtimes.
 *
 * <h2>Key Types</h2>
 *
 * <ul>
 *   <li>{@link express.mvp.myra.transport.Transport} - Primary transport interface
 *   <li>{@link express.mvp.myra.transport.TransportFactory} - Helper for creating transports
 *   <li>{@link express.mvp.myra.transport.TransportConfig} - Configuration for backends and buffers
 *   <li>{@link express.mvp.myra.transport.RegisteredBufferPool} - Pool of registered buffers
 * </ul>
 *
 * @see express.mvp.myra.transport.iouring.IoUringBackend
 * @see express.mvp.myra.transport.nio.NioBackend
 */
package express.mvp.myra.transport;

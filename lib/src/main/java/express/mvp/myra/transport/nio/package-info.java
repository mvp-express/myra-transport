/**
 * Java NIO backend for Myra Transport.
 *
 * <p>This backend provides a cross-platform fallback when io_uring is unavailable, using
 * selector-based non-blocking I/O while keeping the same transport interfaces.
 *
 * @see express.mvp.myra.transport.nio.NioBackend
 */
package express.mvp.myra.transport.nio;

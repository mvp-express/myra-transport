package express.mvp.myra.transport;

import java.lang.foreign.MemorySegment;

/**
 * Abstract adapter class for {@link TransportHandler} with empty default implementations.
 *
 * <p>This adapter allows you to implement only the callback methods you care about, rather than
 * implementing all six methods of {@link TransportHandler}. All methods have empty implementations
 * that do nothing.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * transport.start(new TransportHandlerAdapter() {
 *     @Override
 *     public void onConnected(long token) {
 *         System.out.println("Connected!");
 *     }
 *
 *     @Override
 *     public void onDataReceived(MemorySegment data) {
 *         // Only implement the callbacks you need
 *         processData(data);
 *     }
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is stateless and thread-safe. However, subclasses that add state must ensure their
 * implementations are thread-safe since callbacks are invoked from the I/O thread.
 *
 * @see TransportHandler
 */
public class TransportHandlerAdapter implements TransportHandler {

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void onConnected(long token) {
        // Empty default implementation
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing. Consider logging the failure in production code.
     */
    @Override
    public void onConnectionFailed(long token, Throwable cause) {
        // Empty default implementation
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing. Override to process received data.
     */
    @Override
    public void onDataReceived(MemorySegment data) {
        // Empty default implementation
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void onSendComplete(long token) {
        // Empty default implementation
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing. Consider logging the failure in production code.
     */
    @Override
    public void onSendFailed(long token, Throwable cause) {
        // Empty default implementation
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation does nothing.
     */
    @Override
    public void onClosed() {
        // Empty default implementation
    }
}

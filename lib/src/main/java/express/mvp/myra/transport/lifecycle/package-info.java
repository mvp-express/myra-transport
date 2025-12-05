/**
 * Transport lifecycle management including graceful shutdown support.
 *
 * <p>This package provides components for managing transport lifecycle, particularly around
 * graceful shutdown with proper draining of in-flight operations.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link express.mvp.myra.transport.lifecycle.ShutdownCoordinator} - Coordinates orderly
 *       shutdown with timeout support
 *   <li>{@link express.mvp.myra.transport.lifecycle.ShutdownPhase} - Enumeration of shutdown phases
 *   <li>{@link express.mvp.myra.transport.lifecycle.ShutdownListener} - Callback interface for
 *       shutdown events
 * </ul>
 *
 * <h2>Shutdown Phases</h2>
 *
 * <p>Graceful shutdown proceeds through these phases:
 *
 * <ol>
 *   <li><b>RUNNING:</b> Normal operation
 *   <li><b>DRAINING:</b> Stop accepting new operations, drain in-flight
 *   <li><b>CLOSING:</b> Close connections
 *   <li><b>TERMINATED:</b> All resources released
 * </ol>
 *
 * @see express.mvp.myra.transport.Transport
 */
package express.mvp.myra.transport.lifecycle;

/**
 * Error handling and recovery utilities for transport operations.
 *
 * <p>This package provides components for classifying errors, implementing retry strategies,
 * and managing error recovery in transport operations.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link express.mvp.myra.transport.error.ErrorCategory} - Classification of error types
 *   <li>{@link express.mvp.myra.transport.error.ErrorClassifier} - Classifies exceptions by category
 *   <li>{@link express.mvp.myra.transport.error.RetryPolicy} - Strategy for retry decisions
 *   <li>{@link express.mvp.myra.transport.error.RetryContext} - Tracks retry attempts and state
 * </ul>
 *
 * <h2>Error Categories</h2>
 *
 * <p>Errors are classified into categories to enable appropriate handling:
 *
 * <ul>
 *   <li><b>TRANSIENT:</b> Temporary errors that may succeed on retry (timeouts, busy)
 *   <li><b>NETWORK:</b> Network connectivity issues (connection reset, unreachable)
 *   <li><b>PROTOCOL:</b> Protocol-level errors (invalid framing, bad response)
 *   <li><b>RESOURCE:</b> Resource exhaustion (buffers, file descriptors)
 *   <li><b>FATAL:</b> Unrecoverable errors requiring shutdown
 * </ul>
 *
 * @see express.mvp.myra.transport.lifecycle.ConnectionStateMachine
 */
package express.mvp.myra.transport.error;

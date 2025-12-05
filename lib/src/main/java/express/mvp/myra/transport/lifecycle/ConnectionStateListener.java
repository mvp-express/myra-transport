package express.mvp.myra.transport.lifecycle;

/**
 * Callback interface for connection state change events.
 *
 * <p>Implementations receive notifications when a connection transitions between states,
 * allowing applications to react to connectivity changes.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ConnectionStateMachine machine = new ConnectionStateMachine();
 * machine.addListener((previous, current, cause) -> {
 *     switch (current) {
 *         case CONNECTED:
 *             logger.info("Connection established");
 *             break;
 *         case FAILED:
 *             logger.warn("Connection failed: {}", cause.getMessage());
 *             scheduleReconnect();
 *             break;
 *         case CLOSED:
 *             logger.info("Connection closed");
 *             break;
 *     }
 * });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Callbacks may be invoked from different threads. Implementations should be thread-safe
 * and non-blocking to avoid stalling the transport.
 *
 * @see ConnectionStateMachine
 * @see ConnectionState
 */
@FunctionalInterface
public interface ConnectionStateListener {

    /**
     * Called when the connection state changes.
     *
     * <p>This callback is invoked synchronously during the state transition.
     * Implementations should be quick and non-blocking.
     *
     * @param previousState the state before the transition
     * @param currentState the new state after the transition
     * @param cause the reason for the transition (may be null for normal transitions)
     */
    void onStateChanged(
            ConnectionState previousState,
            ConnectionState currentState,
            Throwable cause);

    /**
     * Called when the connection state changes (without cause information).
     *
     * <p>Default implementation delegates to the three-argument version with null cause.
     *
     * @param previousState the state before the transition
     * @param currentState the new state after the transition
     */
    default void onStateChanged(ConnectionState previousState, ConnectionState currentState) {
        onStateChanged(previousState, currentState, null);
    }
}

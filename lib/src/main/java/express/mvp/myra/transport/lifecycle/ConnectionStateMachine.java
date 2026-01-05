package express.mvp.myra.transport.lifecycle;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe state machine for managing connection lifecycle.
 *
 * <p>This class enforces valid state transitions and notifies listeners of state changes. It
 * provides a robust foundation for connection management with proper state validation.
 *
 * <h2>Valid Transitions</h2>
 *
 * <pre>
 * NEW        → CONNECTING, CLOSED
 * CONNECTING → CONNECTED, FAILED, CLOSING
 * CONNECTED  → CLOSING, FAILED
 * FAILED     → CONNECTING, CLOSED
 * CLOSING    → CLOSED
 * CLOSED     → (terminal, no transitions)
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * ConnectionStateMachine state = new ConnectionStateMachine();
 *
 * // Add listener for state changes
 * state.addListener((prev, curr, cause) -> {
 *     logger.info("State: {} -> {}", prev, curr);
 * });
 *
 * // Connection flow
 * state.transitionTo(ConnectionState.CONNECTING);     // Start connection
 * state.transitionTo(ConnectionState.CONNECTED);      // Success
 *
 * // ... later ...
 * state.transitionTo(ConnectionState.CLOSING);        // Graceful close
 * state.transitionTo(ConnectionState.CLOSED);         // Complete
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are thread-safe. State transitions use atomic operations to ensure consistency
 * even with concurrent access.
 *
 * @see ConnectionState
 * @see ConnectionStateListener
 */
public final class ConnectionStateMachine {

    private static final Logger LOGGER = Logger.getLogger(ConnectionStateMachine.class.getName());

    // Valid transitions from each state
    private static final Set<ConnectionState> FROM_NEW =
            EnumSet.of(ConnectionState.CONNECTING, ConnectionState.CLOSED);

    private static final Set<ConnectionState> FROM_CONNECTING =
            EnumSet.of(ConnectionState.CONNECTED, ConnectionState.FAILED, ConnectionState.CLOSING);

    private static final Set<ConnectionState> FROM_CONNECTED =
            EnumSet.of(ConnectionState.CLOSING, ConnectionState.FAILED);

    private static final Set<ConnectionState> FROM_FAILED =
            EnumSet.of(ConnectionState.CONNECTING, ConnectionState.CLOSED);

    private static final Set<ConnectionState> FROM_CLOSING = EnumSet.of(ConnectionState.CLOSED);

    private static final Set<ConnectionState> FROM_CLOSED = EnumSet.noneOf(ConnectionState.class);

    /** Current connection state. */
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.NEW);

    /** Registered state change listeners. */
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();

    /** Optional identifier for this connection (for logging/debugging). */
    private volatile String connectionId;

    /** Creates a new state machine in {@link ConnectionState#NEW} state. */
    public ConnectionStateMachine() {
        // Default state is NEW
    }

    /**
     * Creates a new state machine with an identifier.
     *
     * @param connectionId identifier for this connection
     */
    public ConnectionStateMachine(String connectionId) {
        this.connectionId = connectionId;
    }

    /**
     * Returns the current state.
     *
     * @return the current connection state
     */
    public ConnectionState getState() {
        return state.get();
    }

    /**
     * Returns the connection identifier.
     *
     * @return the connection ID, or null if not set
     */
    public String getConnectionId() {
        return connectionId;
    }

    /**
     * Sets the connection identifier.
     *
     * @param connectionId the identifier
     */
    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    /**
     * Checks if the connection is active.
     *
     * @return true if in {@link ConnectionState#CONNECTED} state
     */
    public boolean isActive() {
        return state.get().isActive();
    }

    /**
     * Checks if the connection is closed or closing.
     *
     * @return true if in CLOSING or CLOSED state
     */
    public boolean isClosedOrClosing() {
        return state.get().isTerminalOrClosing();
    }

    /**
     * Checks if the connection is fully closed.
     *
     * @return true if in CLOSED state
     */
    public boolean isClosed() {
        return state.get().isClosed();
    }

    /**
     * Registers a listener for state change events.
     *
     * @param listener the listener to register
     */
    public void addListener(ConnectionStateListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     * @return true if the listener was found and removed
     */
    public boolean removeListener(ConnectionStateListener listener) {
        return listeners.remove(listener);
    }

    /**
     * Attempts to transition to a new state.
     *
     * <p>The transition succeeds only if it's a valid transition from the current state. Listeners
     * are notified on successful transition.
     *
     * @param newState the desired new state
     * @return true if the transition was successful
     */
    public boolean transitionTo(ConnectionState newState) {
        return transitionTo(newState, null);
    }

    /**
     * Attempts to transition to a new state with a cause.
     *
     * <p>The cause is passed to listeners and is typically used for failure transitions.
     *
     * @param newState the desired new state
     * @param cause the reason for the transition (may be null)
     * @return true if the transition was successful
     */
    public boolean transitionTo(ConnectionState newState, Throwable cause) {
        while (true) {
            ConnectionState current = state.get();

            // Check if transition is valid
            if (!isValidTransition(current, newState)) {
                return false;
            }

            // Attempt atomic transition
            if (state.compareAndSet(current, newState)) {
                // Notify listeners
                notifyListeners(current, newState, cause);
                return true;
            }
            // CAS failed, retry with new current state
        }
    }

    /**
     * Attempts to transition from a specific expected state.
     *
     * <p>This is useful for conditional transitions where you want to ensure the current state
     * hasn't changed since you checked it.
     *
     * @param expectedState the expected current state
     * @param newState the desired new state
     * @return true if transition successful, false if current state doesn't match
     */
    public boolean transitionFrom(ConnectionState expectedState, ConnectionState newState) {
        return transitionFrom(expectedState, newState, null);
    }

    /**
     * Attempts to transition from a specific expected state with a cause.
     *
     * @param expectedState the expected current state
     * @param newState the desired new state
     * @param cause the reason for the transition (may be null)
     * @return true if transition successful
     */
    public boolean transitionFrom(
            ConnectionState expectedState, ConnectionState newState, Throwable cause) {

        // Check if transition is valid
        if (!isValidTransition(expectedState, newState)) {
            return false;
        }

        // Attempt atomic transition from expected state
        if (state.compareAndSet(expectedState, newState)) {
            notifyListeners(expectedState, newState, cause);
            return true;
        }

        return false;
    }

    /**
     * Forces a transition to a state, bypassing validation.
     *
     * <p><b>Warning:</b> This should only be used in exceptional circumstances, such as error
     * recovery. Normal code should use {@link #transitionTo(ConnectionState)}.
     *
     * @param newState the state to force
     * @param cause the reason for forced transition
     */
    public void forceState(ConnectionState newState, Throwable cause) {
        ConnectionState previous = state.getAndSet(newState);
        if (previous != newState) {
            notifyListeners(previous, newState, cause);
        }
    }

    /**
     * Checks if a transition from one state to another is valid.
     *
     * @param from the source state
     * @param to the target state
     * @return true if the transition is allowed
     */
    public static boolean isValidTransition(ConnectionState from, ConnectionState to) {
        if (from == to) {
            return false; // No self-transitions
        }

        return switch (from) {
            case NEW -> FROM_NEW.contains(to);
            case CONNECTING -> FROM_CONNECTING.contains(to);
            case CONNECTED -> FROM_CONNECTED.contains(to);
            case FAILED -> FROM_FAILED.contains(to);
            case CLOSING -> FROM_CLOSING.contains(to);
            case CLOSED -> FROM_CLOSED.contains(to);
        };
    }

    /**
     * Returns the set of valid target states from a given state.
     *
     * @param from the source state
     * @return set of valid target states
     */
    public static Set<ConnectionState> getValidTransitions(ConnectionState from) {
        return switch (from) {
            case NEW -> EnumSet.copyOf(FROM_NEW);
            case CONNECTING -> EnumSet.copyOf(FROM_CONNECTING);
            case CONNECTED -> EnumSet.copyOf(FROM_CONNECTED);
            case FAILED -> EnumSet.copyOf(FROM_FAILED);
            case CLOSING -> EnumSet.copyOf(FROM_CLOSING);
            case CLOSED -> EnumSet.noneOf(ConnectionState.class);
        };
    }

    /** Notifies all listeners of a state change. */
    private void notifyListeners(
            ConnectionState previous, ConnectionState current, Throwable cause) {

        for (ConnectionStateListener listener : listeners) {
            try {
                listener.onStateChanged(previous, current, cause);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Connection state listener failed", e);
            }
        }
    }

    @Override
    public String toString() {
        String id = connectionId;
        return id != null
                ? "ConnectionStateMachine[" + id + ":" + state.get() + "]"
                : "ConnectionStateMachine[" + state.get() + "]";
    }
}

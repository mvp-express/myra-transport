package express.mvp.myra.transport.lifecycle;

/**
 * Represents the states of a transport connection.
 *
 * <p>The connection state machine tracks the lifecycle of a connection from creation through
 * disconnection. State transitions follow a defined pattern with specific allowed transitions.
 *
 * <h2>State Diagram</h2>
 *
 * <pre>
 *                        ┌────────────────────────────────────┐
 *                        │                                    │
 *                        ▼                                    │
 * ┌────────┐  connect()  ┌────────────┐  success  ┌──────────┐│
 * │  NEW   │────────────▶│ CONNECTING │──────────▶│CONNECTED ││
 * └────────┘             └────────────┘           └──────────┘│
 *                               │                      │      │
 *                               │ failure              │      │
 *                               ▼                      │      │
 *                        ┌────────────┐                │      │
 *                        │  FAILED    │                │      │
 *                        └────────────┘                │      │
 *                               │                      │      │
 *                               │ reconnect()          │ close()
 *                               │                      │      │
 *                               ▼                      ▼      │
 *                        ┌────────────────────────────────────┘
 *                        │
 *                        ▼
 *     ┌────────────┐  drain complete  ┌────────────┐
 *     │  CLOSING   │─────────────────▶│  CLOSED    │
 *     └────────────┘                  └────────────┘
 * </pre>
 *
 * <h2>State Descriptions</h2>
 *
 * <ul>
 *   <li>{@link #NEW}: Initial state - connection not yet initiated
 *   <li>{@link #CONNECTING}: Connection attempt in progress
 *   <li>{@link #CONNECTED}: Actively connected and ready for I/O
 *   <li>{@link #FAILED}: Connection attempt failed (may retry)
 *   <li>{@link #CLOSING}: Graceful close in progress
 *   <li>{@link #CLOSED}: Terminal state - connection fully closed
 * </ul>
 *
 * @see ConnectionStateMachine
 */
public enum ConnectionState {

    /**
     * Initial state before any connection attempt.
     *
     * <p>Allowed transitions:
     *
     * <ul>
     *   <li>{@link #CONNECTING} - when connect() is called
     *   <li>{@link #CLOSED} - if closed before connecting
     * </ul>
     */
    NEW(0, "New", false, false),

    /**
     * Connection attempt in progress.
     *
     * <p>This state indicates an asynchronous connection operation is pending. The transition out
     * of this state depends on the result:
     *
     * <ul>
     *   <li>{@link #CONNECTED} - on success
     *   <li>{@link #FAILED} - on failure
     *   <li>{@link #CLOSING} - if close() called during connection
     * </ul>
     */
    CONNECTING(1, "Connecting", false, false),

    /**
     * Actively connected and ready for I/O operations.
     *
     * <p>In this state, send and receive operations can proceed.
     *
     * <p>Allowed transitions:
     *
     * <ul>
     *   <li>{@link #CLOSING} - when close() is called
     *   <li>{@link #FAILED} - on I/O error (connection reset, etc.)
     * </ul>
     */
    CONNECTED(2, "Connected", true, false),

    /**
     * Connection failed or was lost.
     *
     * <p>This state can be entered from:
     *
     * <ul>
     *   <li>{@link #CONNECTING} - connection attempt failed
     *   <li>{@link #CONNECTED} - connection lost during operation
     * </ul>
     *
     * <p>Allowed transitions:
     *
     * <ul>
     *   <li>{@link #CONNECTING} - on reconnect attempt
     *   <li>{@link #CLOSED} - if close() called or no retry
     * </ul>
     */
    FAILED(3, "Failed", false, false),

    /**
     * Graceful close in progress.
     *
     * <p>During this state:
     *
     * <ul>
     *   <li>No new operations are accepted
     *   <li>In-flight operations may complete
     *   <li>Connection is being drained
     * </ul>
     *
     * <p>Allowed transitions:
     *
     * <ul>
     *   <li>{@link #CLOSED} - when close completes
     * </ul>
     */
    CLOSING(4, "Closing", false, true),

    /**
     * Terminal state - connection fully closed.
     *
     * <p>No transitions are allowed from this state. The connection object cannot be reused.
     */
    CLOSED(5, "Closed", false, true);

    private final int order;
    private final String displayName;
    private final boolean active;
    private final boolean terminal;

    ConnectionState(int order, String displayName, boolean active, boolean terminal) {
        this.order = order;
        this.displayName = displayName;
        this.active = active;
        this.terminal = terminal;
    }

    /**
     * Returns the numeric order of this state.
     *
     * @return the state order
     */
    public int order() {
        return order;
    }

    /**
     * Returns a human-readable name for this state.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Checks if the connection is active and can perform I/O.
     *
     * @return true only in {@link #CONNECTED} state
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Checks if this is a terminal state (CLOSING or CLOSED).
     *
     * @return true if no more operations should be attempted
     */
    public boolean isTerminalOrClosing() {
        return terminal;
    }

    /**
     * Checks if the connection can be reconnected.
     *
     * @return true if in {@link #FAILED} state
     */
    public boolean canReconnect() {
        return this == FAILED;
    }

    /**
     * Checks if a connect operation can be initiated.
     *
     * @return true if in {@link #NEW} or {@link #FAILED} state
     */
    public boolean canConnect() {
        return this == NEW || this == FAILED;
    }

    /**
     * Checks if the connection is in the process of establishing.
     *
     * @return true if in {@link #CONNECTING} state
     */
    public boolean isConnecting() {
        return this == CONNECTING;
    }

    /**
     * Checks if this is a final state with no possible transitions.
     *
     * @return true only in {@link #CLOSED} state
     */
    public boolean isClosed() {
        return this == CLOSED;
    }

    @Override
    public String toString() {
        return displayName;
    }
}

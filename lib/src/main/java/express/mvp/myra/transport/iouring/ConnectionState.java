package express.mvp.myra.transport.iouring;

/**
 * Represents the lifecycle state of a transport connection.
 *
 * <p>This enum tracks the connection state machine for both client and server sockets in the
 * io_uring transport backend. State transitions are thread-safe via volatile field access in the
 * backend.
 *
 * <h2>State Machine</h2>
 *
 * <pre>
 *                    ┌──────────────┐
 *                    │ DISCONNECTED │ (Initial state)
 *                    └──────┬───────┘
 *                           │ connect() called
 *                           ▼
 *                    ┌──────────────┐
 *                    │  CONNECTING  │ (Async connect in progress)
 *                    └──────┬───────┘
 *           ┌───────────────┴───────────────┐
 *           │ success                       │ failure
 *           ▼                               ▼
 *    ┌──────────────┐                ┌──────────────┐
 *    │  CONNECTED   │                │ DISCONNECTED │
 *    └──────┬───────┘                └──────────────┘
 *           │ close() called or error
 *           ▼
 *    ┌──────────────┐
 *    │   CLOSING    │ (Graceful shutdown)
 *    └──────┬───────┘
 *           │ cleanup complete
 *           ▼
 *    ┌──────────────┐
 *    │    CLOSED    │ (Terminal state)
 *    └──────────────┘
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>State transitions should be performed under synchronization in the backend. State checks
 * (canConnect, canDoIO, canClose) can be performed without synchronization for fast-path decisions.
 *
 * @see IoUringBackend#connectionState
 */
public enum ConnectionState {

    /**
     * Initial state - no connection established. Valid operations: {@code connect()}, {@code
     * bind()}
     */
    DISCONNECTED,

    /**
     * Asynchronous connection attempt in progress. The io_uring connect operation has been
     * submitted but not yet completed. No I/O operations are allowed in this state.
     */
    CONNECTING,

    /**
     * Connection established and ready for I/O operations. Valid operations: {@code send()}, {@code
     * receive()}, {@code close()}
     */
    CONNECTED,

    /**
     * Graceful shutdown in progress. Outstanding I/O operations are being drained before final
     * close. No new I/O operations are allowed.
     */
    CLOSING,

    /**
     * Connection closed - terminal state. No operations are allowed. Backend resources have been
     * released.
     */
    CLOSED;

    /**
     * Checks if the connect operation can be initiated in this state.
     *
     * @return true if state is DISCONNECTED (ready for new connection)
     */
    public boolean canConnect() {
        return this == DISCONNECTED;
    }

    /**
     * Checks if I/O operations (send/receive) can be performed in this state.
     *
     * @return true if state is CONNECTED (ready for I/O)
     */
    public boolean canDoIO() {
        return this == CONNECTED;
    }

    /**
     * Checks if the close operation can be initiated in this state.
     *
     * @return true if state is CONNECTED or CONNECTING (active connection to close)
     */
    public boolean canClose() {
        return this == CONNECTED || this == CONNECTING;
    }
}

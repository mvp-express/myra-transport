package express.mvp.myra.transport.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ConnectionState}. */
@DisplayName("ConnectionState")
class ConnectionStateTest {

    @Nested
    @DisplayName("State properties")
    class StatePropertiesTests {

        @Test
        @DisplayName("NEW is not active")
        void new_isNotActive() {
            assertFalse(ConnectionState.NEW.isActive());
        }

        @Test
        @DisplayName("CONNECTING is not active")
        void connecting_isNotActive() {
            assertFalse(ConnectionState.CONNECTING.isActive());
        }

        @Test
        @DisplayName("CONNECTED is active")
        void connected_isActive() {
            assertTrue(ConnectionState.CONNECTED.isActive());
        }

        @Test
        @DisplayName("FAILED is not active")
        void failed_isNotActive() {
            assertFalse(ConnectionState.FAILED.isActive());
        }

        @Test
        @DisplayName("CLOSING is not active")
        void closing_isNotActive() {
            assertFalse(ConnectionState.CLOSING.isActive());
        }

        @Test
        @DisplayName("CLOSED is not active")
        void closed_isNotActive() {
            assertFalse(ConnectionState.CLOSED.isActive());
        }
    }

    @Nested
    @DisplayName("Terminal states")
    class TerminalStateTests {

        @Test
        @DisplayName("Only CLOSING and CLOSED are terminal or closing")
        void terminalOrClosing_correctStates() {
            assertFalse(ConnectionState.NEW.isTerminalOrClosing());
            assertFalse(ConnectionState.CONNECTING.isTerminalOrClosing());
            assertFalse(ConnectionState.CONNECTED.isTerminalOrClosing());
            assertFalse(ConnectionState.FAILED.isTerminalOrClosing());
            assertTrue(ConnectionState.CLOSING.isTerminalOrClosing());
            assertTrue(ConnectionState.CLOSED.isTerminalOrClosing());
        }

        @Test
        @DisplayName("Only CLOSED is fully closed")
        void isClosed_onlyForClosed() {
            assertFalse(ConnectionState.NEW.isClosed());
            assertFalse(ConnectionState.CONNECTING.isClosed());
            assertFalse(ConnectionState.CONNECTED.isClosed());
            assertFalse(ConnectionState.FAILED.isClosed());
            assertFalse(ConnectionState.CLOSING.isClosed());
            assertTrue(ConnectionState.CLOSED.isClosed());
        }
    }

    @Nested
    @DisplayName("Connect capability")
    class ConnectCapabilityTests {

        @Test
        @DisplayName("NEW can connect")
        void new_canConnect() {
            assertTrue(ConnectionState.NEW.canConnect());
        }

        @Test
        @DisplayName("FAILED can connect (reconnect)")
        void failed_canConnect() {
            assertTrue(ConnectionState.FAILED.canConnect());
            assertTrue(ConnectionState.FAILED.canReconnect());
        }

        @Test
        @DisplayName("Other states cannot connect")
        void otherStates_cannotConnect() {
            assertFalse(ConnectionState.CONNECTING.canConnect());
            assertFalse(ConnectionState.CONNECTED.canConnect());
            assertFalse(ConnectionState.CLOSING.canConnect());
            assertFalse(ConnectionState.CLOSED.canConnect());
        }

        @Test
        @DisplayName("Only FAILED can reconnect")
        void onlyFailed_canReconnect() {
            assertFalse(ConnectionState.NEW.canReconnect());
            assertFalse(ConnectionState.CONNECTING.canReconnect());
            assertFalse(ConnectionState.CONNECTED.canReconnect());
            assertTrue(ConnectionState.FAILED.canReconnect());
            assertFalse(ConnectionState.CLOSING.canReconnect());
            assertFalse(ConnectionState.CLOSED.canReconnect());
        }
    }

    @Nested
    @DisplayName("Connecting state")
    class ConnectingStateTests {

        @Test
        @DisplayName("Only CONNECTING is connecting")
        void isConnecting_onlyForConnecting() {
            assertFalse(ConnectionState.NEW.isConnecting());
            assertTrue(ConnectionState.CONNECTING.isConnecting());
            assertFalse(ConnectionState.CONNECTED.isConnecting());
            assertFalse(ConnectionState.FAILED.isConnecting());
            assertFalse(ConnectionState.CLOSING.isConnecting());
            assertFalse(ConnectionState.CLOSED.isConnecting());
        }
    }

    @Nested
    @DisplayName("Display")
    class DisplayTests {

        @Test
        @DisplayName("displayName returns readable names")
        void displayName_returnsReadableNames() {
            assertEquals("New", ConnectionState.NEW.displayName());
            assertEquals("Connecting", ConnectionState.CONNECTING.displayName());
            assertEquals("Connected", ConnectionState.CONNECTED.displayName());
            assertEquals("Failed", ConnectionState.FAILED.displayName());
            assertEquals("Closing", ConnectionState.CLOSING.displayName());
            assertEquals("Closed", ConnectionState.CLOSED.displayName());
        }

        @Test
        @DisplayName("toString returns displayName")
        void toString_returnsDisplayName() {
            for (ConnectionState state : ConnectionState.values()) {
                assertEquals(state.displayName(), state.toString());
            }
        }
    }

    @Nested
    @DisplayName("Order")
    class OrderTests {

        @Test
        @DisplayName("States have distinct orders")
        void states_haveDistinctOrders() {
            assertEquals(0, ConnectionState.NEW.order());
            assertEquals(1, ConnectionState.CONNECTING.order());
            assertEquals(2, ConnectionState.CONNECTED.order());
            assertEquals(3, ConnectionState.FAILED.order());
            assertEquals(4, ConnectionState.CLOSING.order());
            assertEquals(5, ConnectionState.CLOSED.order());
        }
    }
}

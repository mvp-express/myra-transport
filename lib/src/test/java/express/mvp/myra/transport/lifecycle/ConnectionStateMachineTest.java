package express.mvp.myra.transport.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectionStateMachine}.
 */
@DisplayName("ConnectionStateMachine")
class ConnectionStateMachineTest {

    private ConnectionStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new ConnectionStateMachine();
    }

    @Nested
    @DisplayName("Initial state")
    class InitialStateTests {

        @Test
        @DisplayName("Starts in NEW state")
        void startsInNewState() {
            assertEquals(ConnectionState.NEW, machine.getState());
        }

        @Test
        @DisplayName("Is not active initially")
        void notActiveInitially() {
            assertFalse(machine.isActive());
        }

        @Test
        @DisplayName("Is not closed initially")
        void notClosedInitially() {
            assertFalse(machine.isClosed());
            assertFalse(machine.isClosedOrClosing());
        }
    }

    @Nested
    @DisplayName("Connection ID")
    class ConnectionIdTests {

        @Test
        @DisplayName("Connection ID is null by default")
        void connectionIdNullByDefault() {
            assertNull(machine.getConnectionId());
        }

        @Test
        @DisplayName("Can set connection ID")
        void canSetConnectionId() {
            machine.setConnectionId("conn-123");
            assertEquals("conn-123", machine.getConnectionId());
        }

        @Test
        @DisplayName("Constructor accepts connection ID")
        void constructorAcceptsConnectionId() {
            ConnectionStateMachine m = new ConnectionStateMachine("test-conn");
            assertEquals("test-conn", m.getConnectionId());
        }

        @Test
        @DisplayName("toString includes connection ID")
        void toStringIncludesConnectionId() {
            machine.setConnectionId("my-id");
            String str = machine.toString();
            assertTrue(str.contains("my-id"));
            assertTrue(str.contains(machine.getState().toString()));
        }
    }

    @Nested
    @DisplayName("Valid transitions from NEW")
    class TransitionsFromNewTests {

        @Test
        @DisplayName("NEW -> CONNECTING is valid")
        void newToConnecting_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }

        @Test
        @DisplayName("NEW -> CLOSED is valid")
        void newToClosed_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CLOSED));
            assertEquals(ConnectionState.CLOSED, machine.getState());
        }

        @Test
        @DisplayName("NEW -> CONNECTED is invalid")
        void newToConnected_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.CONNECTED));
            assertEquals(ConnectionState.NEW, machine.getState());
        }

        @Test
        @DisplayName("NEW -> FAILED is invalid")
        void newToFailed_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.FAILED));
            assertEquals(ConnectionState.NEW, machine.getState());
        }
    }

    @Nested
    @DisplayName("Valid transitions from CONNECTING")
    class TransitionsFromConnectingTests {

        @BeforeEach
        void setup() {
            machine.transitionTo(ConnectionState.CONNECTING);
        }

        @Test
        @DisplayName("CONNECTING -> CONNECTED is valid")
        void connectingToConnected_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CONNECTED));
            assertEquals(ConnectionState.CONNECTED, machine.getState());
        }

        @Test
        @DisplayName("CONNECTING -> FAILED is valid")
        void connectingToFailed_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.FAILED));
            assertEquals(ConnectionState.FAILED, machine.getState());
        }

        @Test
        @DisplayName("CONNECTING -> CLOSING is valid")
        void connectingToClosing_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CLOSING));
            assertEquals(ConnectionState.CLOSING, machine.getState());
        }

        @Test
        @DisplayName("CONNECTING -> NEW is invalid")
        void connectingToNew_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.NEW));
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }
    }

    @Nested
    @DisplayName("Valid transitions from CONNECTED")
    class TransitionsFromConnectedTests {

        @BeforeEach
        void setup() {
            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.CONNECTED);
        }

        @Test
        @DisplayName("CONNECTED -> CLOSING is valid")
        void connectedToClosing_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CLOSING));
            assertEquals(ConnectionState.CLOSING, machine.getState());
        }

        @Test
        @DisplayName("CONNECTED -> FAILED is valid")
        void connectedToFailed_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.FAILED));
            assertEquals(ConnectionState.FAILED, machine.getState());
        }

        @Test
        @DisplayName("CONNECTED -> CLOSED is invalid (must go through CLOSING)")
        void connectedToClosed_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.CLOSED));
            assertEquals(ConnectionState.CONNECTED, machine.getState());
        }
    }

    @Nested
    @DisplayName("Valid transitions from FAILED")
    class TransitionsFromFailedTests {

        @BeforeEach
        void setup() {
            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.FAILED);
        }

        @Test
        @DisplayName("FAILED -> CONNECTING is valid (reconnect)")
        void failedToConnecting_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }

        @Test
        @DisplayName("FAILED -> CLOSED is valid")
        void failedToClosed_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CLOSED));
            assertEquals(ConnectionState.CLOSED, machine.getState());
        }

        @Test
        @DisplayName("FAILED -> CONNECTED is invalid")
        void failedToConnected_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.CONNECTED));
            assertEquals(ConnectionState.FAILED, machine.getState());
        }
    }

    @Nested
    @DisplayName("Valid transitions from CLOSING")
    class TransitionsFromClosingTests {

        @BeforeEach
        void setup() {
            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.CONNECTED);
            machine.transitionTo(ConnectionState.CLOSING);
        }

        @Test
        @DisplayName("CLOSING -> CLOSED is valid")
        void closingToClosed_isValid() {
            assertTrue(machine.transitionTo(ConnectionState.CLOSED));
            assertEquals(ConnectionState.CLOSED, machine.getState());
        }

        @Test
        @DisplayName("CLOSING -> any other state is invalid")
        void closingToOther_isInvalid() {
            assertFalse(machine.transitionTo(ConnectionState.NEW));
            assertFalse(machine.transitionTo(ConnectionState.CONNECTING));
            assertFalse(machine.transitionTo(ConnectionState.CONNECTED));
            assertFalse(machine.transitionTo(ConnectionState.FAILED));
            assertEquals(ConnectionState.CLOSING, machine.getState());
        }
    }

    @Nested
    @DisplayName("Transitions from CLOSED")
    class TransitionsFromClosedTests {

        @BeforeEach
        void setup() {
            machine.transitionTo(ConnectionState.CLOSED);
        }

        @Test
        @DisplayName("CLOSED is terminal - no transitions allowed")
        void closedIsTerminal() {
            for (ConnectionState target : ConnectionState.values()) {
                if (target != ConnectionState.CLOSED) {
                    assertFalse(machine.transitionTo(target),
                            "Should not transition from CLOSED to " + target);
                }
            }
            assertEquals(ConnectionState.CLOSED, machine.getState());
        }
    }

    @Nested
    @DisplayName("Self-transitions")
    class SelfTransitionTests {

        @Test
        @DisplayName("Self-transitions are not allowed")
        void selfTransitions_notAllowed() {
            for (ConnectionState state : ConnectionState.values()) {
                assertFalse(ConnectionStateMachine.isValidTransition(state, state),
                        "Self-transition should not be valid for " + state);
            }
        }
    }

    @Nested
    @DisplayName("Transition with cause")
    class TransitionWithCauseTests {

        @Test
        @DisplayName("Can transition with cause")
        void canTransitionWithCause() {
            RuntimeException cause = new RuntimeException("Connection refused");
            machine.transitionTo(ConnectionState.CONNECTING);
            assertTrue(machine.transitionTo(ConnectionState.FAILED, cause));
            assertEquals(ConnectionState.FAILED, machine.getState());
        }
    }

    @Nested
    @DisplayName("Conditional transitions")
    class ConditionalTransitionTests {

        @Test
        @DisplayName("transitionFrom succeeds when state matches")
        void transitionFrom_succeedsWhenStateMatches() {
            assertTrue(machine.transitionFrom(
                    ConnectionState.NEW,
                    ConnectionState.CONNECTING));
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }

        @Test
        @DisplayName("transitionFrom fails when state doesn't match")
        void transitionFrom_failsWhenStateMismatch() {
            assertFalse(machine.transitionFrom(
                    ConnectionState.CONNECTING,
                    ConnectionState.CONNECTED));
            assertEquals(ConnectionState.NEW, machine.getState());
        }

        @Test
        @DisplayName("transitionFrom fails for invalid transition")
        void transitionFrom_failsForInvalidTransition() {
            assertFalse(machine.transitionFrom(
                    ConnectionState.NEW,
                    ConnectionState.CONNECTED));
            assertEquals(ConnectionState.NEW, machine.getState());
        }
    }

    @Nested
    @DisplayName("Force state")
    class ForceStateTests {

        @Test
        @DisplayName("forceState bypasses validation")
        void forceState_bypassesValidation() {
            // This would normally be invalid: NEW -> CONNECTED
            machine.forceState(ConnectionState.CONNECTED, null);
            assertEquals(ConnectionState.CONNECTED, machine.getState());
        }

        @Test
        @DisplayName("forceState notifies listeners")
        void forceState_notifiesListeners() {
            AtomicReference<ConnectionState> receivedState = new AtomicReference<>();
            machine.addListener((prev, curr, cause) -> receivedState.set(curr));

            machine.forceState(ConnectionState.FAILED, new RuntimeException("Forced"));
            assertEquals(ConnectionState.FAILED, receivedState.get());
        }
    }

    @Nested
    @DisplayName("Listeners")
    class ListenerTests {

        @Test
        @DisplayName("Listener receives state changes")
        void listener_receivesStateChanges() {
            List<ConnectionState> states = new ArrayList<>();
            machine.addListener((prev, curr, cause) -> states.add(curr));

            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.CONNECTED);
            machine.transitionTo(ConnectionState.CLOSING);
            machine.transitionTo(ConnectionState.CLOSED);

            assertEquals(4, states.size());
            assertEquals(ConnectionState.CONNECTING, states.get(0));
            assertEquals(ConnectionState.CONNECTED, states.get(1));
            assertEquals(ConnectionState.CLOSING, states.get(2));
            assertEquals(ConnectionState.CLOSED, states.get(3));
        }

        @Test
        @DisplayName("Listener receives previous state")
        void listener_receivesPreviousState() {
            AtomicReference<ConnectionState> receivedPrevious = new AtomicReference<>();
            machine.addListener((prev, curr, cause) -> receivedPrevious.set(prev));

            machine.transitionTo(ConnectionState.CONNECTING);
            assertEquals(ConnectionState.NEW, receivedPrevious.get());

            machine.transitionTo(ConnectionState.CONNECTED);
            assertEquals(ConnectionState.CONNECTING, receivedPrevious.get());
        }

        @Test
        @DisplayName("Listener receives cause")
        void listener_receivesCause() {
            AtomicReference<Throwable> receivedCause = new AtomicReference<>();
            machine.addListener((prev, curr, cause) -> receivedCause.set(cause));

            RuntimeException error = new RuntimeException("Connection lost");
            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.FAILED, error);

            assertSame(error, receivedCause.get());
        }

        @Test
        @DisplayName("Removed listener does not receive events")
        void removedListener_doesNotReceiveEvents() {
            AtomicInteger calls = new AtomicInteger(0);
            ConnectionStateListener listener =
                    (prev, curr, cause) -> calls.incrementAndGet();

            machine.addListener(listener);
            machine.transitionTo(ConnectionState.CONNECTING);
            assertEquals(1, calls.get());

            assertTrue(machine.removeListener(listener));
            machine.transitionTo(ConnectionState.CONNECTED);
            assertEquals(1, calls.get()); // No additional call
        }

        @Test
        @DisplayName("Listener errors do not break state machine")
        void listenerErrors_doNotBreakStateMachine() {
            machine.addListener((prev, curr, cause) -> {
                throw new RuntimeException("Listener error");
            });

            // Should not throw
            assertDoesNotThrow(() -> machine.transitionTo(ConnectionState.CONNECTING));
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }
    }

    @Nested
    @DisplayName("Static validation methods")
    class StaticValidationTests {

        @Test
        @DisplayName("isValidTransition returns correct results")
        void isValidTransition_returnsCorrectResults() {
            // Valid
            assertTrue(ConnectionStateMachine.isValidTransition(
                    ConnectionState.NEW, ConnectionState.CONNECTING));
            assertTrue(ConnectionStateMachine.isValidTransition(
                    ConnectionState.CONNECTING, ConnectionState.CONNECTED));
            assertTrue(ConnectionStateMachine.isValidTransition(
                    ConnectionState.CONNECTED, ConnectionState.CLOSING));
            assertTrue(ConnectionStateMachine.isValidTransition(
                    ConnectionState.CLOSING, ConnectionState.CLOSED));

            // Invalid
            assertFalse(ConnectionStateMachine.isValidTransition(
                    ConnectionState.NEW, ConnectionState.CONNECTED));
            assertFalse(ConnectionStateMachine.isValidTransition(
                    ConnectionState.CLOSED, ConnectionState.NEW));
        }

        @Test
        @DisplayName("getValidTransitions returns all valid targets")
        void getValidTransitions_returnsAllValidTargets() {
            Set<ConnectionState> fromNew =
                    ConnectionStateMachine.getValidTransitions(ConnectionState.NEW);
            assertTrue(fromNew.contains(ConnectionState.CONNECTING));
            assertTrue(fromNew.contains(ConnectionState.CLOSED));
            assertEquals(2, fromNew.size());

            Set<ConnectionState> fromClosed =
                    ConnectionStateMachine.getValidTransitions(ConnectionState.CLOSED);
            assertTrue(fromClosed.isEmpty());
        }
    }

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent transitions are handled safely")
        void concurrentTransitions_handledSafely() throws InterruptedException {
            int numThreads = 10;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);

            // All threads try to transition NEW -> CONNECTING
            for (int i = 0; i < numThreads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        if (machine.transitionTo(ConnectionState.CONNECTING)) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            // Exactly one thread should succeed
            assertEquals(1, successCount.get());
            assertEquals(ConnectionState.CONNECTING, machine.getState());
        }

        @Test
        @DisplayName("Concurrent listeners receive all notifications")
        void concurrentListeners_receiveAllNotifications() throws InterruptedException {
            List<ConnectionState> received = Collections.synchronizedList(new ArrayList<>());
            machine.addListener((prev, curr, cause) -> received.add(curr));

            // Rapid transitions
            ExecutorService executor = Executors.newFixedThreadPool(4);

            executor.submit(() -> machine.transitionTo(ConnectionState.CONNECTING));
            Thread.sleep(10);
            executor.submit(() -> machine.transitionTo(ConnectionState.CONNECTED));
            Thread.sleep(10);
            executor.submit(() -> machine.transitionTo(ConnectionState.CLOSING));
            Thread.sleep(10);
            executor.submit(() -> machine.transitionTo(ConnectionState.CLOSED));

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // All transitions should be recorded
            assertEquals(4, received.size());
        }
    }

    @Nested
    @DisplayName("Complete connection lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Happy path: connect, send, close")
        void happyPath_connectSendClose() {
            List<ConnectionState> transitions = new ArrayList<>();
            machine.addListener((prev, curr, cause) -> transitions.add(curr));

            // Connect
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
            assertTrue(machine.transitionTo(ConnectionState.CONNECTED));
            assertTrue(machine.isActive());

            // Close gracefully
            assertTrue(machine.transitionTo(ConnectionState.CLOSING));
            assertTrue(machine.isClosedOrClosing());
            assertFalse(machine.isActive());

            assertTrue(machine.transitionTo(ConnectionState.CLOSED));
            assertTrue(machine.isClosed());

            assertEquals(4, transitions.size());
        }

        @Test
        @DisplayName("Failure path: connect fails, retry, succeed")
        void failurePath_connectFailsRetrySucceed() {
            // First attempt fails
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
            assertTrue(machine.transitionTo(ConnectionState.FAILED));

            // Retry
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
            assertTrue(machine.transitionTo(ConnectionState.CONNECTED));
            assertTrue(machine.isActive());
        }

        @Test
        @DisplayName("Connection lost during operation")
        void connectionLost_duringOperation() {
            // Connect successfully
            machine.transitionTo(ConnectionState.CONNECTING);
            machine.transitionTo(ConnectionState.CONNECTED);

            // Connection lost
            RuntimeException cause = new RuntimeException("Connection reset by peer");
            assertTrue(machine.transitionTo(ConnectionState.FAILED, cause));

            // Can retry
            assertTrue(machine.getState().canReconnect());
            assertTrue(machine.transitionTo(ConnectionState.CONNECTING));
        }
    }
}

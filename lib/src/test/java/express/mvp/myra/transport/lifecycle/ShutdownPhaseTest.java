package express.mvp.myra.transport.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShutdownPhase}.
 */
@DisplayName("ShutdownPhase")
class ShutdownPhaseTest {

    @Nested
    @DisplayName("Phase ordering")
    class PhaseOrderingTests {

        @Test
        @DisplayName("RUNNING has lowest order")
        void running_hasLowestOrder() {
            assertEquals(0, ShutdownPhase.RUNNING.order());
        }

        @Test
        @DisplayName("TERMINATED has highest order")
        void terminated_hasHighestOrder() {
            assertEquals(3, ShutdownPhase.TERMINATED.order());
        }

        @Test
        @DisplayName("Phases are in correct order")
        void phases_inCorrectOrder() {
            assertTrue(ShutdownPhase.RUNNING.order() < ShutdownPhase.DRAINING.order());
            assertTrue(ShutdownPhase.DRAINING.order() < ShutdownPhase.CLOSING.order());
            assertTrue(ShutdownPhase.CLOSING.order() < ShutdownPhase.TERMINATED.order());
        }

        @Test
        @DisplayName("isBefore returns correct result")
        void isBefore_returnsCorrectResult() {
            assertTrue(ShutdownPhase.RUNNING.isBefore(ShutdownPhase.DRAINING));
            assertTrue(ShutdownPhase.RUNNING.isBefore(ShutdownPhase.CLOSING));
            assertTrue(ShutdownPhase.RUNNING.isBefore(ShutdownPhase.TERMINATED));
            assertTrue(ShutdownPhase.DRAINING.isBefore(ShutdownPhase.CLOSING));
            assertTrue(ShutdownPhase.CLOSING.isBefore(ShutdownPhase.TERMINATED));

            assertFalse(ShutdownPhase.RUNNING.isBefore(ShutdownPhase.RUNNING));
            assertFalse(ShutdownPhase.TERMINATED.isBefore(ShutdownPhase.RUNNING));
        }

        @Test
        @DisplayName("isAtOrAfter returns correct result")
        void isAtOrAfter_returnsCorrectResult() {
            assertTrue(ShutdownPhase.RUNNING.isAtOrAfter(ShutdownPhase.RUNNING));
            assertTrue(ShutdownPhase.DRAINING.isAtOrAfter(ShutdownPhase.RUNNING));
            assertTrue(ShutdownPhase.CLOSING.isAtOrAfter(ShutdownPhase.DRAINING));
            assertTrue(ShutdownPhase.TERMINATED.isAtOrAfter(ShutdownPhase.CLOSING));

            assertFalse(ShutdownPhase.RUNNING.isAtOrAfter(ShutdownPhase.DRAINING));
            assertFalse(ShutdownPhase.DRAINING.isAtOrAfter(ShutdownPhase.CLOSING));
        }
    }

    @Nested
    @DisplayName("State queries")
    class StateQueryTests {

        @Test
        @DisplayName("RUNNING accepts operations")
        void running_acceptsOperations() {
            assertTrue(ShutdownPhase.RUNNING.isAcceptingOperations());
        }

        @Test
        @DisplayName("Non-RUNNING phases reject operations")
        void nonRunning_rejectsOperations() {
            assertFalse(ShutdownPhase.DRAINING.isAcceptingOperations());
            assertFalse(ShutdownPhase.CLOSING.isAcceptingOperations());
            assertFalse(ShutdownPhase.TERMINATED.isAcceptingOperations());
        }

        @Test
        @DisplayName("RUNNING is not shutting down")
        void running_isNotShuttingDown() {
            assertFalse(ShutdownPhase.RUNNING.isShuttingDown());
        }

        @Test
        @DisplayName("Non-RUNNING phases are shutting down")
        void nonRunning_isShuttingDown() {
            assertTrue(ShutdownPhase.DRAINING.isShuttingDown());
            assertTrue(ShutdownPhase.CLOSING.isShuttingDown());
            assertTrue(ShutdownPhase.TERMINATED.isShuttingDown());
        }

        @Test
        @DisplayName("Only TERMINATED is terminated")
        void onlyTerminated_isTerminated() {
            assertFalse(ShutdownPhase.RUNNING.isTerminated());
            assertFalse(ShutdownPhase.DRAINING.isTerminated());
            assertFalse(ShutdownPhase.CLOSING.isTerminated());
            assertTrue(ShutdownPhase.TERMINATED.isTerminated());
        }
    }

    @Nested
    @DisplayName("Display")
    class DisplayTests {

        @Test
        @DisplayName("displayName returns readable name")
        void displayName_returnsReadableName() {
            assertEquals("Running", ShutdownPhase.RUNNING.displayName());
            assertEquals("Draining", ShutdownPhase.DRAINING.displayName());
            assertEquals("Closing", ShutdownPhase.CLOSING.displayName());
            assertEquals("Terminated", ShutdownPhase.TERMINATED.displayName());
        }

        @Test
        @DisplayName("toString returns displayName")
        void toString_returnsDisplayName() {
            for (ShutdownPhase phase : ShutdownPhase.values()) {
                assertEquals(phase.displayName(), phase.toString());
            }
        }
    }
}

package express.mvp.myra.transport.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShutdownCoordinator}.
 */
@DisplayName("ShutdownCoordinator")
class ShutdownCoordinatorTest {

    private ShutdownCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ShutdownCoordinator();
    }

    @Nested
    @DisplayName("Initial state")
    class InitialStateTests {

        @Test
        @DisplayName("Starts in RUNNING phase")
        void startsInRunningPhase() {
            assertEquals(ShutdownPhase.RUNNING, coordinator.getPhase());
        }

        @Test
        @DisplayName("Accepts operations initially")
        void acceptsOperationsInitially() {
            assertTrue(coordinator.isAcceptingOperations());
        }

        @Test
        @DisplayName("Not terminated initially")
        void notTerminatedInitially() {
            assertFalse(coordinator.isTerminated());
        }

        @Test
        @DisplayName("Zero in-flight operations initially")
        void zeroInFlightInitially() {
            assertEquals(0, coordinator.getInFlightCount());
        }
    }

    @Nested
    @DisplayName("Operation tracking")
    class OperationTrackingTests {

        @Test
        @DisplayName("operationStarted increments count")
        void operationStarted_incrementsCount() {
            assertTrue(coordinator.operationStarted());
            assertEquals(1, coordinator.getInFlightCount());

            assertTrue(coordinator.operationStarted());
            assertEquals(2, coordinator.getInFlightCount());
        }

        @Test
        @DisplayName("operationCompleted decrements count")
        void operationCompleted_decrementsCount() {
            coordinator.operationStarted();
            coordinator.operationStarted();
            assertEquals(2, coordinator.getInFlightCount());

            coordinator.operationCompleted();
            assertEquals(1, coordinator.getInFlightCount());

            coordinator.operationCompleted();
            assertEquals(0, coordinator.getInFlightCount());
        }

        @Test
        @DisplayName("operationCompleted does not go negative")
        void operationCompleted_doesNotGoNegative() {
            coordinator.operationCompleted();
            coordinator.operationCompleted();
            assertEquals(0, coordinator.getInFlightCount());
        }
    }

    @Nested
    @DisplayName("Graceful shutdown")
    class GracefulShutdownTests {

        @Test
        @DisplayName("Shutdown with no in-flight operations is immediate")
        void shutdown_noInFlightOps_isImmediate() throws InterruptedException {
            AtomicBoolean connectionsClosed = new AtomicBoolean(false);
            AtomicBoolean resourcesReleased = new AtomicBoolean(false);

            boolean graceful = coordinator.shutdown(
                    Duration.ofSeconds(1),
                    () -> connectionsClosed.set(true),
                    () -> resourcesReleased.set(true));

            assertTrue(graceful);
            assertTrue(connectionsClosed.get());
            assertTrue(resourcesReleased.get());
            assertEquals(ShutdownPhase.TERMINATED, coordinator.getPhase());
        }

        @Test
        @DisplayName("Shutdown waits for in-flight operations")
        void shutdown_waitsForInFlightOps() throws InterruptedException {
            // Start an operation
            coordinator.operationStarted();

            AtomicBoolean shutdownComplete = new AtomicBoolean(false);

            // Start shutdown in background
            Thread shutdownThread = new Thread(() -> {
                try {
                    coordinator.shutdown(Duration.ofSeconds(2), () -> {}, () -> {});
                    shutdownComplete.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            shutdownThread.start();

            // Give time for shutdown to start
            Thread.sleep(100);

            // Verify still in DRAINING phase
            assertEquals(ShutdownPhase.DRAINING, coordinator.getPhase());
            assertFalse(shutdownComplete.get());

            // Complete the operation
            coordinator.operationCompleted();

            // Wait for shutdown to complete
            shutdownThread.join(1000);
            assertTrue(shutdownComplete.get());
            assertEquals(ShutdownPhase.TERMINATED, coordinator.getPhase());
        }

        @Test
        @DisplayName("Shutdown times out if operations don't complete")
        void shutdown_timesOut_ifOpsNotComplete() throws InterruptedException {
            // Start an operation that won't complete
            coordinator.operationStarted();

            long start = System.currentTimeMillis();
            boolean graceful = coordinator.shutdown(
                    Duration.ofMillis(100),
                    () -> {},
                    () -> {});
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(graceful, "Should not be graceful due to timeout");
            assertTrue(elapsed >= 100, "Should have waited at least the timeout");
            assertEquals(ShutdownPhase.TERMINATED, coordinator.getPhase());
        }

        @Test
        @DisplayName("Rejects new operations during shutdown")
        void rejectsNewOperations_duringShutdown() throws InterruptedException {
            // Start shutdown in background (with an in-flight op to make it wait)
            coordinator.operationStarted();

            Thread shutdownThread = new Thread(() -> {
                try {
                    coordinator.shutdown(Duration.ofMillis(200), () -> {}, () -> {});
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            shutdownThread.start();

            // Give time for shutdown to start
            Thread.sleep(50);

            // Try to start new operation - should be rejected
            assertFalse(coordinator.operationStarted());

            // Clean up
            coordinator.operationCompleted();
            shutdownThread.join(1000);
        }
    }

    @Nested
    @DisplayName("Immediate shutdown")
    class ImmediateShutdownTests {

        @Test
        @DisplayName("shutdownNow skips draining")
        void shutdownNow_skipsDraining() {
            // Start operations that would block graceful shutdown
            coordinator.operationStarted();
            coordinator.operationStarted();

            coordinator.shutdownNow(() -> {}, () -> {});

            // Should immediately be TERMINATED
            assertEquals(ShutdownPhase.TERMINATED, coordinator.getPhase());
        }

        @Test
        @DisplayName("shutdownNow calls closers")
        void shutdownNow_callsClosers() {
            AtomicBoolean connectionsClosed = new AtomicBoolean(false);
            AtomicBoolean resourcesReleased = new AtomicBoolean(false);

            coordinator.shutdownNow(
                    () -> connectionsClosed.set(true),
                    () -> resourcesReleased.set(true));

            assertTrue(connectionsClosed.get());
            assertTrue(resourcesReleased.get());
        }

        @Test
        @DisplayName("shutdownNow is idempotent")
        void shutdownNow_isIdempotent() {
            AtomicInteger closeCount = new AtomicInteger(0);

            coordinator.shutdownNow(() -> closeCount.incrementAndGet(), () -> {});
            coordinator.shutdownNow(() -> closeCount.incrementAndGet(), () -> {});

            // Second call should not run the closer again
            assertEquals(1, closeCount.get());
        }
    }

    @Nested
    @DisplayName("Listeners")
    class ListenerTests {

        @Test
        @DisplayName("Listener receives phase changes")
        void listener_receivesPhaseChanges() throws InterruptedException {
            List<ShutdownPhase> phases = Collections.synchronizedList(new ArrayList<>());

            coordinator.addListener(new ShutdownListener() {
                @Override
                public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
                    phases.add(current);
                }

                @Override
                public void onShutdownComplete(boolean graceful, long durationMs) {
                    // Record completion
                }
            });

            coordinator.shutdown(Duration.ofSeconds(1), () -> {}, () -> {});

            assertEquals(3, phases.size());
            assertEquals(ShutdownPhase.DRAINING, phases.get(0));
            assertEquals(ShutdownPhase.CLOSING, phases.get(1));
            assertEquals(ShutdownPhase.TERMINATED, phases.get(2));
        }

        @Test
        @DisplayName("Listener receives shutdown complete notification")
        void listener_receivesShutdownComplete() throws InterruptedException {
            AtomicBoolean gracefulReceived = new AtomicBoolean(false);
            AtomicLong durationReceived = new AtomicLong(0);

            coordinator.addListener(new ShutdownListener() {
                @Override
                public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
                }

                @Override
                public void onShutdownComplete(boolean graceful, long durationMs) {
                    gracefulReceived.set(graceful);
                    durationReceived.set(durationMs);
                }
            });

            coordinator.shutdown(Duration.ofSeconds(1), () -> {}, () -> {});

            assertTrue(gracefulReceived.get());
            assertTrue(durationReceived.get() >= 0);
        }

        @Test
        @DisplayName("Listener receives drain progress")
        void listener_receivesDrainProgress() throws InterruptedException {
            AtomicInteger progressCalls = new AtomicInteger(0);
            AtomicReference<int[]> lastProgress = new AtomicReference<>();

            coordinator.addListener(new ShutdownListener() {
                @Override
                public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
                }

                @Override
                public void onDrainProgress(int remaining, int total) {
                    progressCalls.incrementAndGet();
                    lastProgress.set(new int[]{remaining, total});
                }

                @Override
                public void onShutdownComplete(boolean graceful, long durationMs) {
                }
            });

            // Start operations
            coordinator.operationStarted();
            coordinator.operationStarted();

            // Start shutdown in background
            Thread shutdownThread = new Thread(() -> {
                try {
                    coordinator.shutdown(Duration.ofSeconds(1), () -> {}, () -> {});
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            shutdownThread.start();

            // Give time for drain to start
            Thread.sleep(50);

            // Complete operations
            coordinator.operationCompleted();
            Thread.sleep(10);
            coordinator.operationCompleted();

            shutdownThread.join(1000);

            assertTrue(progressCalls.get() >= 1, "Should receive at least one progress update");
        }

        @Test
        @DisplayName("Removed listener does not receive events")
        void removedListener_doesNotReceiveEvents() throws InterruptedException {
            AtomicInteger calls = new AtomicInteger(0);

            ShutdownListener listener = new ShutdownListener() {
                @Override
                public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
                    calls.incrementAndGet();
                }

                @Override
                public void onShutdownComplete(boolean graceful, long durationMs) {
                    calls.incrementAndGet();
                }
            };

            coordinator.addListener(listener);
            assertTrue(coordinator.removeListener(listener));

            coordinator.shutdown(Duration.ofSeconds(1), () -> {}, () -> {});

            assertEquals(0, calls.get());
        }

        @Test
        @DisplayName("Listener errors do not break shutdown")
        void listenerErrors_doNotBreakShutdown() throws InterruptedException {
            coordinator.addListener(new ShutdownListener() {
                @Override
                public void onPhaseChange(ShutdownPhase previous, ShutdownPhase current) {
                    throw new RuntimeException("Listener error");
                }

                @Override
                public void onShutdownComplete(boolean graceful, long durationMs) {
                    throw new RuntimeException("Listener error");
                }
            });

            // Should not throw
            boolean graceful = coordinator.shutdown(Duration.ofSeconds(1), () -> {}, () -> {});

            assertTrue(graceful);
            assertTrue(coordinator.isTerminated());
        }
    }

    @Nested
    @DisplayName("Concurrent operations")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent operation tracking is thread-safe")
        void concurrentOperationTracking_isThreadSafe() throws InterruptedException {
            int numThreads = 8;
            int opsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int i = 0; i < numThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < opsPerThread; j++) {
                            if (coordinator.operationStarted()) {
                                // Simulate some work
                                Thread.sleep(1);
                                coordinator.operationCompleted();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            // All operations should have completed
            assertEquals(0, coordinator.getInFlightCount());
        }

        @Test
        @DisplayName("Concurrent shutdown requests are handled correctly")
        void concurrentShutdownRequests_handledCorrectly() throws InterruptedException {
            int numThreads = 4;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        boolean result = coordinator.shutdown(
                                Duration.ofSeconds(1),
                                () -> {},
                                () -> {});
                        successCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

            // All threads should complete
            assertEquals(numThreads, successCount.get());
            assertTrue(coordinator.isTerminated());
        }

        @Test
        @DisplayName("Operations rejected after shutdown initiated")
        void operationsRejected_afterShutdownInitiated() throws InterruptedException {
            // Start an operation to make shutdown wait
            coordinator.operationStarted();

            CountDownLatch shutdownStarted = new CountDownLatch(1);
            AtomicInteger rejectedOps = new AtomicInteger(0);

            // Start shutdown in background
            Thread shutdownThread = new Thread(() -> {
                try {
                    shutdownStarted.countDown();
                    coordinator.shutdown(Duration.ofMillis(500), () -> {}, () -> {});
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            shutdownThread.start();

            // Wait for shutdown to start
            shutdownStarted.await();
            Thread.sleep(50); // Give time for phase to change

            // Try starting operations - should be rejected
            for (int i = 0; i < 10; i++) {
                if (!coordinator.operationStarted()) {
                    rejectedOps.incrementAndGet();
                }
            }

            // Complete original operation and wait for shutdown
            coordinator.operationCompleted();
            shutdownThread.join(1000);

            assertEquals(10, rejectedOps.get(), "All operations should be rejected");
        }
    }

    @Nested
    @DisplayName("Await termination")
    class AwaitTerminationTests {

        @Test
        @DisplayName("awaitTermination returns true when already terminated")
        void awaitTermination_returnsTrueWhenTerminated() throws InterruptedException {
            coordinator.shutdownNow(() -> {}, () -> {});

            assertTrue(coordinator.awaitTermination(Duration.ofMillis(100)));
        }

        @Test
        @DisplayName("awaitTermination times out if not terminated")
        void awaitTermination_timesOutIfNotTerminated() throws InterruptedException {
            // Keep an operation open to prevent termination
            coordinator.operationStarted();

            // Start shutdown in background (will wait for our operation)
            Thread shutdownThread = new Thread(() -> {
                try {
                    coordinator.shutdown(Duration.ofSeconds(10), () -> {}, () -> {});
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            shutdownThread.start();

            // Wait should timeout
            long start = System.currentTimeMillis();
            assertFalse(coordinator.awaitTermination(Duration.ofMillis(100)));
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed >= 100 && elapsed < 500);

            // Clean up
            coordinator.operationCompleted();
            shutdownThread.join(1000);
        }
    }
}

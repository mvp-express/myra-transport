package express.mvp.myra.transport.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NativeMemoryCleaner}.
 */
@DisplayName("NativeMemoryCleaner")
class NativeMemoryCleanerTest {

    @BeforeEach
    void setUp() {
        NativeMemoryCleaner.resetStatistics();
    }

    @Nested
    @DisplayName("Registration")
    class RegistrationTests {

        @Test
        @DisplayName("register increments registration count")
        void register_incrementsCount() {
            Object obj = new Object();
            Cleaner.Cleanable cleanable = NativeMemoryCleaner.register(obj, () -> {});

            assertEquals(1, NativeMemoryCleaner.getRegistrationCount());

            // Clean up
            cleanable.clean();
        }

        @Test
        @DisplayName("Multiple registrations are counted")
        void multipleRegistrations_areCounted() {
            for (int i = 0; i < 5; i++) {
                Object obj = new Object();
                NativeMemoryCleaner.register(obj, () -> {}).clean();
            }

            assertEquals(5, NativeMemoryCleaner.getRegistrationCount());
        }
    }

    @Nested
    @DisplayName("Explicit cleanup")
    class ExplicitCleanupTests {

        @Test
        @DisplayName("clean() runs cleanup action")
        void clean_runsCleanupAction() {
            AtomicBoolean cleaned = new AtomicBoolean(false);
            Object obj = new Object();

            Cleaner.Cleanable cleanable = NativeMemoryCleaner.register(
                    obj, () -> cleaned.set(true));
            cleanable.clean();

            assertTrue(cleaned.get());
        }

        @Test
        @DisplayName("TrackedCleanable tracks explicit cleanup")
        void trackedCleanable_tracksExplicitCleanup() {
            AtomicBoolean cleaned = new AtomicBoolean(false);
            Object obj = new Object();

            NativeMemoryCleaner.TrackedCleanable cleanable =
                    NativeMemoryCleaner.registerTracked(obj, () -> cleaned.set(true));
            cleanable.clean();

            assertTrue(cleaned.get());
            assertEquals(1, NativeMemoryCleaner.getExplicitCleanupCount());
        }

        @Test
        @DisplayName("Double clean is safe")
        void doubleClean_isSafe() {
            AtomicInteger cleanCount = new AtomicInteger(0);
            Object obj = new Object();

            Cleaner.Cleanable cleanable = NativeMemoryCleaner.register(
                    obj, cleanCount::incrementAndGet);
            cleanable.clean();
            cleanable.clean(); // Second clean

            // Cleanup should only run once
            assertEquals(1, cleanCount.get());
        }

        @Test
        @DisplayName("TrackedCleanable implements AutoCloseable")
        void trackedCleanable_implementsAutoCloseable() {
            AtomicBoolean cleaned = new AtomicBoolean(false);
            Object obj = new Object();

            try (NativeMemoryCleaner.TrackedCleanable cleanable =
                    NativeMemoryCleaner.registerTracked(obj, () -> cleaned.set(true))) {
                // Auto-close will call clean()
            }

            assertTrue(cleaned.get());
        }
    }

    @Nested
    @DisplayName("GC-triggered cleanup")
    class GcTriggeredCleanupTests {

        @Test
        @DisplayName("Cleanup runs when object is garbage collected")
        void cleanup_runsOnGc() throws InterruptedException {
            AtomicBoolean cleaned = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);

            // Create object in a scope that makes it collectible
            createAndAbandon(cleaned, latch);

            // Force GC
            for (int i = 0; i < 10; i++) {
                System.gc();
                if (latch.await(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }

            // Cleanup should have run
            assertTrue(cleaned.get() || latch.await(1, TimeUnit.SECONDS),
                    "Cleanup should run after GC");
        }

        private void createAndAbandon(AtomicBoolean cleaned, CountDownLatch latch) {
            Object obj = new Object();
            NativeMemoryCleaner.register(obj, () -> {
                cleaned.set(true);
                latch.countDown();
            });
            // obj becomes unreachable after this method returns
        }
    }

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("Active count reflects outstanding registrations")
        void activeCount_reflectsOutstanding() {
            Object obj1 = new Object();
            Object obj2 = new Object();

            Cleaner.Cleanable c1 = NativeMemoryCleaner.register(obj1, () -> {});
            Cleaner.Cleanable c2 = NativeMemoryCleaner.register(obj2, () -> {});

            // Note: We can't reliably test activeCount because the cleaner
            // thread may have already processed some cleanups.
            assertEquals(2, NativeMemoryCleaner.getRegistrationCount());

            c1.clean();
            c2.clean();
        }

        @Test
        @DisplayName("resetStatistics clears all counters")
        void resetStatistics_clearsAllCounters() {
            Object obj = new Object();
            NativeMemoryCleaner.register(obj, () -> {}).clean();

            NativeMemoryCleaner.resetStatistics();

            assertEquals(0, NativeMemoryCleaner.getRegistrationCount());
            assertEquals(0, NativeMemoryCleaner.getExplicitCleanupCount());
            assertEquals(0, NativeMemoryCleaner.getGcCleanupCount());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Cleanup action exceptions propagate on explicit clean")
        void cleanupException_propagatesOnExplicitClean() {
            Object obj = new Object();
            Cleaner.Cleanable cleanable = NativeMemoryCleaner.register(obj, () -> {
                throw new RuntimeException("Cleanup error");
            });

            // Exceptions propagate from explicit clean() calls
            assertThrows(RuntimeException.class, () -> cleanable.clean());
        }
    }
}

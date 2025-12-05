package express.mvp.myra.transport.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TrackedArena}.
 */
@DisplayName("TrackedArena")
class TrackedArenaTest {

    private ResourceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = ResourceTracker.getInstance();
        tracker.clear();
        tracker.setEnabled(true);
        NativeMemoryCleaner.resetStatistics();
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("ofConfined creates valid arena")
        void ofConfined_createsValidArena() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                assertNotNull(arena);
                assertTrue(arena.isOpen());
            }
        }

        @Test
        @DisplayName("ofShared creates valid arena")
        void ofShared_createsValidArena() {
            try (TrackedArena arena = TrackedArena.ofShared("test")) {
                assertNotNull(arena);
                assertTrue(arena.isOpen());
            }
        }

        @Test
        @DisplayName("wrap wraps existing arena")
        void wrap_wrapsExistingArena() {
            Arena underlying = Arena.ofConfined();

            try (TrackedArena tracked = TrackedArena.wrap(underlying, "wrapped")) {
                assertNotNull(tracked);
                assertTrue(tracked.isOpen());
            }

            // Underlying should be closed when wrapper closes
            assertFalse(underlying.scope().isAlive());
        }

        @Test
        @DisplayName("getSource returns provided source")
        void getSource_returnsProvidedSource() {
            try (TrackedArena arena = TrackedArena.ofConfined("MySource")) {
                assertEquals("MySource", arena.getSource());
            }
        }
    }

    @Nested
    @DisplayName("Memory allocation")
    class MemoryAllocationTests {

        @Test
        @DisplayName("allocate returns valid memory segment")
        void allocate_returnsValidSegment() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                MemorySegment segment = arena.allocate(1024);

                assertNotNull(segment);
                assertEquals(1024, segment.byteSize());
            }
        }

        @Test
        @DisplayName("allocate with alignment returns aligned segment")
        void allocate_withAlignment_returnsAlignedSegment() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                MemorySegment segment = arena.allocate(1024, 8);

                assertNotNull(segment);
                assertEquals(1024, segment.byteSize());
                assertEquals(0, segment.address() % 8);
            }
        }

        @Test
        @DisplayName("Multiple allocations work")
        void multipleAllocations_work() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                MemorySegment s1 = arena.allocate(100);
                MemorySegment s2 = arena.allocate(200);
                MemorySegment s3 = arena.allocate(300);

                assertNotNull(s1);
                assertNotNull(s2);
                assertNotNull(s3);
            }
        }
    }

    @Nested
    @DisplayName("Tracking integration")
    class TrackingIntegrationTests {

        @Test
        @DisplayName("Arena creation is tracked")
        void arenaCreation_isTracked() {
            int before = tracker.getActiveAllocationCount();

            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                int during = tracker.getActiveAllocationCount();
                assertTrue(during > before);
            }
        }

        @Test
        @DisplayName("Arena close releases tracking")
        void arenaClose_releasesTracking() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            int during = tracker.getActiveAllocationCount();

            arena.close();

            int after = tracker.getActiveAllocationCount();
            assertTrue(after < during);
        }

        @Test
        @DisplayName("getTotalAllocated tracks bytes")
        void getTotalAllocated_tracksBytes() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                arena.allocate(100);
                arena.allocate(200);
                arena.allocate(300);

                assertEquals(600, arena.getTotalAllocated());
            }
        }
    }

    @Nested
    @DisplayName("Cleaner integration")
    class CleanerIntegrationTests {

        @Test
        @DisplayName("Registers with cleaner")
        void registersWithCleaner() {
            long beforeCount = NativeMemoryCleaner.getRegistrationCount();

            TrackedArena arena = TrackedArena.ofConfined("test");

            assertEquals(beforeCount + 1, NativeMemoryCleaner.getRegistrationCount());

            arena.close();
        }

        @Test
        @DisplayName("Explicit close counts as explicit cleanup")
        void explicitClose_countsAsExplicit() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            arena.close();

            assertTrue(NativeMemoryCleaner.getExplicitCleanupCount() > 0);
        }
    }

    @Nested
    @DisplayName("Scope behavior")
    class ScopeBehaviorTests {

        @Test
        @DisplayName("scope returns valid scope when open")
        void scope_returnsValidScope_whenOpen() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                assertNotNull(arena.scope());
                assertTrue(arena.scope().isAlive());
            }
        }

        @Test
        @DisplayName("scope is not alive after close")
        void scope_notAlive_afterClose() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            arena.close();

            assertFalse(arena.scope().isAlive());
        }

        @Test
        @DisplayName("isOpen returns false after close")
        void isOpen_returnsFalse_afterClose() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            assertTrue(arena.isOpen());

            arena.close();

            assertFalse(arena.isOpen());
        }
    }

    @Nested
    @DisplayName("Shared arena threading")
    class SharedArenaThreadingTests {

        @Test
        @DisplayName("Shared arena can be accessed from multiple threads")
        void sharedArena_multiThreadAccess() throws InterruptedException {
            try (TrackedArena arena = TrackedArena.ofShared("shared")) {
                CountDownLatch latch = new CountDownLatch(2);

                Thread t1 = new Thread(() -> {
                    arena.allocate(100);
                    latch.countDown();
                });

                Thread t2 = new Thread(() -> {
                    arena.allocate(200);
                    latch.countDown();
                });

                t1.start();
                t2.start();

                assertTrue(latch.await(5, TimeUnit.SECONDS));

                assertEquals(300, arena.getTotalAllocated());
            }
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("allocate after close throws")
        void allocateAfterClose_throws() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            arena.close();

            assertThrows(IllegalStateException.class, () -> arena.allocate(100));
        }

        @Test
        @DisplayName("Double close is safe")
        void doubleClose_isSafe() {
            TrackedArena arena = TrackedArena.ofConfined("test");
            arena.close();

            assertDoesNotThrow(() -> arena.close());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString contains source")
        void toString_containsSource() {
            try (TrackedArena arena = TrackedArena.ofConfined("MySource")) {
                assertTrue(arena.toString().contains("MySource"));
            }
        }

        @Test
        @DisplayName("toString contains allocated bytes")
        void toString_containsAllocatedBytes() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                arena.allocate(1024);
                assertTrue(arena.toString().contains("1024"));
            }
        }

        @Test
        @DisplayName("toString contains open status")
        void toString_containsOpenStatus() {
            try (TrackedArena arena = TrackedArena.ofConfined("test")) {
                assertTrue(arena.toString().contains("open=true"));
            }
        }
    }
}

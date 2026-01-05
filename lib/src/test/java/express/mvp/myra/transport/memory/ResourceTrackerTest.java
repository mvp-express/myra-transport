package express.mvp.myra.transport.memory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResourceTracker}. */
@DisplayName("ResourceTracker")
class ResourceTrackerTest {

    private ResourceTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = ResourceTracker.getInstance();
        tracker.clear();
        tracker.setEnabled(true);
        tracker.setCaptureStackTraces(false);
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
        tracker.setEnabled(false);
    }

    @Nested
    @DisplayName("Enable/disable")
    class EnableDisableTests {

        @Test
        @DisplayName("setEnabled enables tracking")
        void setEnabled_enablesTracking() {
            tracker.setEnabled(true);
            assertTrue(tracker.isEnabled());
        }

        @Test
        @DisplayName("setEnabled can disable tracking")
        void setEnabled_canDisable() {
            tracker.setEnabled(false);
            assertFalse(tracker.isEnabled());
        }
    }

    @Nested
    @DisplayName("Allocation tracking")
    class AllocationTrackingTests {

        @Test
        @DisplayName("trackAllocation returns non-zero ID when enabled")
        void trackAllocation_returnsNonZeroId() {
            long id = tracker.trackAllocation("test", 1024);
            assertTrue(id > 0);
        }

        @Test
        @DisplayName("trackAllocation returns 0 when disabled")
        void trackAllocation_returnsZero_whenDisabled() {
            tracker.setEnabled(false);
            long id = tracker.trackAllocation("test", 1024);
            assertEquals(0, id);
        }

        @Test
        @DisplayName("Multiple allocations get unique IDs")
        void multipleAllocations_getUniqueIds() {
            long id1 = tracker.trackAllocation("test1", 100);
            long id2 = tracker.trackAllocation("test2", 200);
            long id3 = tracker.trackAllocation("test3", 300);

            assertNotEquals(id1, id2);
            assertNotEquals(id2, id3);
            assertNotEquals(id1, id3);
        }

        @Test
        @DisplayName("Active allocations are tracked")
        void activeAllocations_areTracked() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            assertEquals(2, active.size());
        }

        @Test
        @DisplayName("getActiveAllocationCount returns correct count")
        void getActiveAllocationCount_returnsCorrectCount() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);
            tracker.trackAllocation("test3", 300);

            assertEquals(3, tracker.getActiveAllocationCount());
        }
    }

    @Nested
    @DisplayName("Release tracking")
    class ReleaseTrackingTests {

        @Test
        @DisplayName("trackRelease removes allocation")
        void trackRelease_removesAllocation() {
            long id = tracker.trackAllocation("test", 1024);

            assertEquals(1, tracker.getActiveAllocationCount());

            boolean released = tracker.trackRelease(id);

            assertTrue(released);
            assertEquals(0, tracker.getActiveAllocationCount());
        }

        @Test
        @DisplayName("trackRelease returns false for unknown ID")
        void trackRelease_returnsFalse_forUnknownId() {
            boolean released = tracker.trackRelease(999999L);
            assertFalse(released);
        }

        @Test
        @DisplayName("Double release returns false")
        void doubleRelease_returnsFalse() {
            long id = tracker.trackAllocation("test", 1024);
            assertTrue(tracker.trackRelease(id));
            assertFalse(tracker.trackRelease(id));
        }

        @Test
        @DisplayName("trackRelease returns false when disabled")
        void trackRelease_returnsFalse_whenDisabled() {
            long id = tracker.trackAllocation("test", 1024);
            tracker.setEnabled(false);
            assertFalse(tracker.trackRelease(id));
        }
    }

    @Nested
    @DisplayName("AllocationRecord")
    class AllocationRecordTests {

        @Test
        @DisplayName("AllocationRecord contains source")
        void allocationRecord_containsSource() {
            tracker.trackAllocation("MySource", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertEquals("MySource", record.getSource());
        }

        @Test
        @DisplayName("AllocationRecord contains size")
        void allocationRecord_containsSize() {
            tracker.trackAllocation("test", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertEquals(1024, record.getSize());
        }

        @Test
        @DisplayName("AllocationRecord contains allocation time")
        void allocationRecord_containsTime() {
            tracker.trackAllocation("test", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertNotNull(record.getAllocationTime());
        }

        @Test
        @DisplayName("AllocationRecord captures stack trace when enabled")
        void allocationRecord_capturesStackTrace() {
            tracker.setCaptureStackTraces(true);
            tracker.trackAllocation("test", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertNotNull(record.getStackTrace());
            assertTrue(record.getStackTrace().length > 0);
        }

        @Test
        @DisplayName("AllocationRecord has null stack trace when capture disabled")
        void allocationRecord_nullStackTrace_whenCaptureDisabled() {
            tracker.setCaptureStackTraces(false);
            tracker.trackAllocation("test", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertNull(record.getStackTrace());
        }

        @Test
        @DisplayName("AllocationRecord getAgeMillis returns non-negative")
        void allocationRecord_getAgeMillis_nonNegative() {
            tracker.trackAllocation("test", 1024);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            assertTrue(record.getAgeMillis() >= 0);
        }

        @Test
        @DisplayName("AllocationRecord toString contains info")
        void allocationRecord_toString_containsInfo() {
            tracker.trackAllocation("MySource", 2048);

            Collection<ResourceTracker.AllocationRecord> active = tracker.getActiveAllocations();
            ResourceTracker.AllocationRecord record = active.iterator().next();

            String str = record.toString();
            assertTrue(str.contains("MySource"));
            assertTrue(str.contains("2048"));
        }
    }

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("Tracks total allocated bytes")
        void tracksTotalAllocated() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);
            tracker.trackAllocation("test3", 300);

            assertEquals(600, tracker.getTotalAllocated());
        }

        @Test
        @DisplayName("Tracks total released bytes")
        void tracksTotalReleased() {
            long id1 = tracker.trackAllocation("test1", 100);
            long id2 = tracker.trackAllocation("test2", 200);

            assertTrue(tracker.trackRelease(id1));
            assertTrue(tracker.trackRelease(id2));

            assertEquals(300, tracker.getTotalReleased());
        }

        @Test
        @DisplayName("Tracks allocation count")
        void tracksAllocationCount() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);
            tracker.trackAllocation("test3", 300);

            assertEquals(3, tracker.getAllocationCount());
        }

        @Test
        @DisplayName("Tracks release count")
        void tracksReleaseCount() {
            long id1 = tracker.trackAllocation("test1", 100);
            long id2 = tracker.trackAllocation("test2", 200);

            assertTrue(tracker.trackRelease(id1));
            assertTrue(tracker.trackRelease(id2));

            assertEquals(2, tracker.getReleaseCount());
        }

        @Test
        @DisplayName("getActiveAllocationBytes sums active sizes")
        void getActiveAllocationBytes_sumsActiveSizes() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);
            long id3 = tracker.trackAllocation("test3", 300);

            assertTrue(tracker.trackRelease(id3));

            assertEquals(300, tracker.getActiveAllocationBytes());
        }

        @Test
        @DisplayName("getSummary returns formatted string")
        void getSummary_returnsFormattedString() {
            tracker.trackAllocation("test", 1024);

            String summary = tracker.getSummary();

            assertTrue(summary.contains("enabled=true"));
            assertTrue(summary.contains("active=1"));
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent allocations are thread-safe")
        void concurrentAllocations_areThreadSafe() throws InterruptedException {
            int threadCount = 10;
            int allocationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                int threadId = t;
                executor.execute(
                        () -> {
                            try {
                                for (int i = 0; i < allocationsPerThread; i++) {
                                    tracker.trackAllocation("thread-" + threadId, 100);
                                }
                            } finally {
                                latch.countDown();
                            }
                        });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals((long) threadCount * allocationsPerThread, tracker.getAllocationCount());
        }
    }

    @Nested
    @DisplayName("Clear")
    class ClearTests {

        @Test
        @DisplayName("clear removes all active allocations")
        void clear_removesAllActive() {
            tracker.trackAllocation("test1", 100);
            tracker.trackAllocation("test2", 200);

            tracker.clear();

            assertEquals(0, tracker.getActiveAllocationCount());
        }

        @Test
        @DisplayName("clear resets statistics")
        void clear_resetsStatistics() {
            tracker.trackAllocation("test", 1024);

            tracker.clear();

            assertEquals(0, tracker.getTotalAllocated());
            assertEquals(0, tracker.getAllocationCount());
        }
    }
}

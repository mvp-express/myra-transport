package express.mvp.myra.transport.memory;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks native memory allocations for debugging and leak detection.
 *
 * <p>This tracker maintains a registry of all active allocations with their metadata,
 * enabling detection of memory leaks and analysis of allocation patterns.
 *
 * <h2>Design Goals</h2>
 *
 * <ul>
 *   <li><b>Low overhead:</b> Thread-safe but minimal synchronization
 *   <li><b>Rich metadata:</b> Captures allocation site, size, time
 *   <li><b>Leak detection:</b> Identifies unreleased allocations at shutdown
 *   <li><b>Configurable:</b> Can be disabled in production
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Enable tracking
 * ResourceTracker tracker = ResourceTracker.getInstance();
 * tracker.setEnabled(true);
 * tracker.setCaptureStackTraces(true); // Expensive, use in dev only
 *
 * // Track allocation
 * long id = tracker.trackAllocation("BufferPool", 65536);
 *
 * // ... use resource ...
 *
 * // Track release
 * tracker.trackRelease(id);
 *
 * // At shutdown, check for leaks
 * Collection<AllocationRecord> leaks = tracker.getActiveAllocations();
 * if (!leaks.isEmpty()) {
 *     logger.warn("Memory leak detected: {} unreleased allocations", leaks.size());
 *     for (AllocationRecord leak : leaks) {
 *         logger.warn("  Leaked: {} ({} bytes, allocated at {})",
 *             leak.getSource(), leak.getSize(), leak.getAllocationTime());
 *     }
 * }
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 *   <li>Stack trace capture is expensive - disable in production
 *   <li>ConcurrentHashMap provides good concurrent performance
 *   <li>ID generation uses AtomicLong for lock-free operation
 * </ul>
 *
 * @see NativeMemoryCleaner
 */
public final class ResourceTracker {

    /** Singleton instance. */
    private static final ResourceTracker INSTANCE = new ResourceTracker();

    /** Map of allocation ID to record. */
    private final Map<Long, AllocationRecord> allocations = new ConcurrentHashMap<>();

    /** ID generator for allocations. */
    private final AtomicLong idGenerator = new AtomicLong(1);

    /** Total allocated bytes (cumulative). */
    private final AtomicLong totalAllocated = new AtomicLong(0);

    /** Total released bytes (cumulative). */
    private final AtomicLong totalReleased = new AtomicLong(0);

    /** Total allocation count. */
    private final AtomicLong allocationCount = new AtomicLong(0);

    /** Total release count. */
    private final AtomicLong releaseCount = new AtomicLong(0);

    /** Whether tracking is enabled. */
    private volatile boolean enabled = false;

    /** Whether to capture stack traces (expensive). */
    private volatile boolean captureStackTraces = false;

    private ResourceTracker() {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     *
     * @return the tracker instance
     */
    public static ResourceTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Enables or disables tracking.
     *
     * @param enabled true to enable tracking
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Checks if tracking is enabled.
     *
     * @return true if tracking is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables stack trace capture.
     *
     * <p><b>Warning:</b> Stack trace capture is expensive and should only be enabled during
     * development or debugging.
     *
     * @param capture true to capture stack traces
     */
    public void setCaptureStackTraces(boolean capture) {
        this.captureStackTraces = capture;
    }

    /**
     * Tracks a new allocation.
     *
     * @param source identifier for the allocation source (e.g., "BufferPool")
     * @param sizeBytes the allocation size in bytes
     * @return allocation ID for later release tracking
     */
    public long trackAllocation(String source, long sizeBytes) {
        if (!enabled) {
            return 0; // Tracking disabled
        }

        long id = idGenerator.getAndIncrement();
        StackTraceElement[] stackTrace = captureStackTraces
                ? Thread.currentThread().getStackTrace()
                : null;

        AllocationRecord record = new AllocationRecord(
                id, source, sizeBytes, Instant.now(), stackTrace);

        allocations.put(id, record);
        totalAllocated.addAndGet(sizeBytes);
        allocationCount.incrementAndGet();

        return id;
    }

    /**
     * Tracks release of an allocation.
     *
     * @param allocationId the ID returned from trackAllocation
     * @return true if the allocation was found and removed
     */
    public boolean trackRelease(long allocationId) {
        if (!enabled || allocationId == 0) {
            return false;
        }

        AllocationRecord record = allocations.remove(allocationId);
        if (record != null) {
            totalReleased.addAndGet(record.getSize());
            releaseCount.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Returns all active (unreleased) allocations.
     *
     * @return collection of active allocation records
     */
    public Collection<AllocationRecord> getActiveAllocations() {
        return Collections.unmodifiableCollection(allocations.values());
    }

    /**
     * Returns the number of active allocations.
     *
     * @return active allocation count
     */
    public int getActiveAllocationCount() {
        return allocations.size();
    }

    /**
     * Returns the total bytes currently allocated.
     *
     * @return total active allocation size
     */
    public long getActiveAllocationBytes() {
        return allocations.values().stream()
                .mapToLong(AllocationRecord::getSize)
                .sum();
    }

    /**
     * Returns cumulative allocated bytes.
     *
     * @return total allocated bytes
     */
    public long getTotalAllocated() {
        return totalAllocated.get();
    }

    /**
     * Returns cumulative released bytes.
     *
     * @return total released bytes
     */
    public long getTotalReleased() {
        return totalReleased.get();
    }

    /**
     * Returns total allocation count.
     *
     * @return allocation count
     */
    public long getAllocationCount() {
        return allocationCount.get();
    }

    /**
     * Returns total release count.
     *
     * @return release count
     */
    public long getReleaseCount() {
        return releaseCount.get();
    }

    /**
     * Clears all tracking data.
     */
    public void clear() {
        allocations.clear();
        totalAllocated.set(0);
        totalReleased.set(0);
        allocationCount.set(0);
        releaseCount.set(0);
        idGenerator.set(1);
    }

    /**
     * Returns a summary of tracking statistics.
     *
     * @return formatted statistics string
     */
    public String getSummary() {
        return String.format(
                "ResourceTracker[enabled=%s, active=%d (%d bytes), total=%d allocs / %d releases]",
                enabled,
                getActiveAllocationCount(),
                getActiveAllocationBytes(),
                allocationCount.get(),
                releaseCount.get());
    }

    /**
     * Record of a tracked allocation.
     */
    public static final class AllocationRecord {
        private final long id;
        private final String source;
        private final long size;
        private final Instant allocationTime;
        private final StackTraceElement[] stackTrace;

        AllocationRecord(
                long id,
                String source,
                long size,
                Instant allocationTime,
                StackTraceElement[] stackTrace) {
            this.id = id;
            this.source = source;
            this.size = size;
            this.allocationTime = allocationTime;
            this.stackTrace = stackTrace;
        }

        /**
         * Returns the allocation ID.
         *
         * @return the ID
         */
        public long getId() {
            return id;
        }

        /**
         * Returns the source identifier.
         *
         * @return the source
         */
        public String getSource() {
            return source;
        }

        /**
         * Returns the allocation size in bytes.
         *
         * @return the size
         */
        public long getSize() {
            return size;
        }

        /**
         * Returns when the allocation occurred.
         *
         * @return the allocation time
         */
        public Instant getAllocationTime() {
            return allocationTime;
        }

        /**
         * Returns the stack trace at allocation (may be null).
         *
         * @return the stack trace or null
         */
        public StackTraceElement[] getStackTrace() {
            return stackTrace;
        }

        /**
         * Returns the age of this allocation.
         *
         * @return age in milliseconds
         */
        public long getAgeMillis() {
            return Instant.now().toEpochMilli() - allocationTime.toEpochMilli();
        }

        @Override
        public String toString() {
            return String.format(
                    "AllocationRecord[id=%d, source=%s, size=%d, age=%dms]",
                    id, source, size, getAgeMillis());
        }
    }
}

package express.mvp.myra.transport.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Arena wrapper that integrates with ResourceTracker and NativeMemoryCleaner.
 *
 * <p>This class wraps a standard Arena with tracking and cleanup support, ensuring
 * memory is properly managed throughout its lifecycle.
 *
 * <h2>Features</h2>
 *
 * <ul>
 *   <li><b>Allocation tracking:</b> All allocations registered with ResourceTracker
 *   <li><b>Automatic cleanup:</b> Cleaner registration for GC-triggered release
 *   <li><b>Statistics:</b> Tracks total allocations and releases
 *   <li><b>Thread safety:</b> Safe for use from multiple threads (if underlying arena allows)
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * try (TrackedArena arena = TrackedArena.ofShared("BufferPool")) {
 *     MemorySegment buffer = arena.allocate(65536);
 *     // Use buffer...
 * } // Automatically releases all allocations
 * }</pre>
 *
 * <h2>Arena Types</h2>
 *
 * <ul>
 *   <li>{@link #ofConfined(String)} - Single-thread only, fastest
 *   <li>{@link #ofShared(String)} - Multi-thread safe
 *   <li>{@link #wrap(Arena, String)} - Wrap an existing arena
 * </ul>
 *
 * @see Arena
 * @see ResourceTracker
 * @see NativeMemoryCleaner
 */
public final class TrackedArena implements AutoCloseable {

    /** The wrapped arena. */
    private final Arena delegate;

    /** Identifier for tracking. */
    private final String source;

    /** Cleaner registration for safety. */
    private final NativeMemoryCleaner.TrackedCleanable cleanable;

    /** Tracking ID for the arena itself. */
    private final long trackingId;

    /** Total bytes allocated. */
    private long totalAllocated = 0;

    /** Whether the arena has been closed. */
    private volatile boolean closed = false;

    private TrackedArena(Arena delegate, String source) {
        this.delegate = delegate;
        this.source = source;

        // Track the arena allocation
        ResourceTracker tracker = ResourceTracker.getInstance();
        this.trackingId = tracker.trackAllocation(source + ":arena", 0);

        // Register cleaner as safety net
        // Capture only what's needed, not 'this'
        Arena arenaRef = this.delegate;
        long trackId = this.trackingId;
        this.cleanable = NativeMemoryCleaner.registerTracked(this, () -> {
            if (!arenaRef.scope().isAlive()) {
                return; // Already closed
            }
            try {
                arenaRef.close();
            } catch (IllegalStateException e) {
                // Arena already closed or in use
            }
            ResourceTracker.getInstance().trackRelease(trackId);
        });
    }

    /**
     * Creates a tracked confined arena.
     *
     * <p>Confined arenas can only be accessed from the thread that created them,
     * but provide the best performance.
     *
     * @param source identifier for tracking
     * @return a new tracked confined arena
     */
    public static TrackedArena ofConfined(String source) {
        return new TrackedArena(Arena.ofConfined(), source);
    }

    /**
     * Creates a tracked shared arena.
     *
     * <p>Shared arenas can be accessed from any thread, suitable for concurrent use.
     *
     * @param source identifier for tracking
     * @return a new tracked shared arena
     */
    public static TrackedArena ofShared(String source) {
        return new TrackedArena(Arena.ofShared(), source);
    }

    /**
     * Wraps an existing arena with tracking.
     *
     * <p><b>Note:</b> The wrapped arena's lifecycle is now managed by this wrapper.
     * The original arena should not be closed directly.
     *
     * @param arena the arena to wrap
     * @param source identifier for tracking
     * @return a tracked wrapper
     */
    public static TrackedArena wrap(Arena arena, String source) {
        return new TrackedArena(arena, source);
    }

    /**
     * Allocates memory from this arena with tracking.
     *
     * @param byteSize the size in bytes
     * @return the allocated memory segment
     * @throws IllegalStateException if the arena is closed
     */
    public MemorySegment allocate(long byteSize) {
        checkOpen();
        totalAllocated += byteSize;

        ResourceTracker tracker = ResourceTracker.getInstance();
        if (tracker.isEnabled()) {
            tracker.trackAllocation(source + ":segment", byteSize);
        }

        return delegate.allocate(byteSize);
    }

    /**
     * Allocates aligned memory from this arena with tracking.
     *
     * @param byteSize the size in bytes
     * @param byteAlignment the required alignment
     * @return the allocated memory segment
     * @throws IllegalStateException if the arena is closed
     */
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        checkOpen();
        totalAllocated += byteSize;

        ResourceTracker tracker = ResourceTracker.getInstance();
        if (tracker.isEnabled()) {
            tracker.trackAllocation(source + ":segment", byteSize);
        }

        return delegate.allocate(byteSize, byteAlignment);
    }

    /**
     * Returns the underlying arena's scope.
     *
     * @return the scope
     */
    public MemorySegment.Scope scope() {
        return delegate.scope();
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
     * Returns total bytes allocated from this arena.
     *
     * @return total allocated bytes
     */
    public long getTotalAllocated() {
        return totalAllocated;
    }

    /**
     * Checks if this arena is still open.
     *
     * @return true if open
     */
    public boolean isOpen() {
        return !closed && delegate.scope().isAlive();
    }

    private void checkOpen() {
        if (closed || !delegate.scope().isAlive()) {
            throw new IllegalStateException("Arena is closed");
        }
    }

    /**
     * Closes this arena and releases all memory.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Use cleanable for proper cleanup and statistics
        cleanable.clean();
    }

    @Override
    public String toString() {
        return String.format(
                "TrackedArena[source=%s, allocated=%d bytes, open=%s]",
                source, totalAllocated, isOpen());
    }
}

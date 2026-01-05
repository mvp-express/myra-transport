/**
 * Native memory management utilities for the transport layer.
 *
 * <p>This package provides components for safe management of native (off-heap) memory, ensuring
 * resources are properly released even in exceptional circumstances.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link express.mvp.myra.transport.memory.NativeMemoryCleaner} - Automatic cleanup using
 *       Cleaner API
 *   <li>{@link express.mvp.myra.transport.memory.ResourceTracker} - Tracks allocations for leak
 *       detection
 *   <li>{@link express.mvp.myra.transport.memory.TrackedArena} - Arena wrapper with tracking
 * </ul>
 *
 * <h2>Memory Safety Strategy</h2>
 *
 * <p>The transport uses several mechanisms to ensure memory safety:
 *
 * <ol>
 *   <li><b>Buffer pooling:</b> RegisteredBufferPool reuses native buffers efficiently
 *   <li><b>Cleaner registration:</b> Phantom reference cleanup for GC-triggered release
 *   <li><b>Resource tracking:</b> Debug mode tracks all allocations with stack traces
 *   <li><b>Arena lifecycle:</b> Shared arenas with explicit scope management
 * </ol>
 *
 * <h2>Best Practices</h2>
 *
 * <ul>
 *   <li>Always use try-with-resources for deterministic cleanup
 *   <li>Enable resource tracking during development to detect leaks
 *   <li>Register cleaners as a safety net, not primary cleanup mechanism
 * </ul>
 *
 * @see java.lang.ref.Cleaner
 * @see java.lang.foreign.Arena
 */
package express.mvp.myra.transport.memory;

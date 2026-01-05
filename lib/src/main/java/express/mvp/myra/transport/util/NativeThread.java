package express.mvp.myra.transport.util;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Utility for native thread management using the Foreign Function &amp; Memory API.
 *
 * <p>This class provides low-level Linux thread operations for performance optimization, including
 * CPU affinity pinning to reduce context switching and improve cache locality.
 *
 * <h2>CPU Affinity</h2>
 *
 * <p>Pinning a thread to a specific CPU core provides several benefits:
 *
 * <ul>
 *   <li><b>Cache locality:</b> L1/L2 cache stays warm on the same core
 *   <li><b>Reduced latency variance:</b> No migration between cores
 *   <li><b>Predictable performance:</b> Consistent timing for latency-sensitive operations
 *   <li><b>NUMA awareness:</b> Can pin to cores near specific memory
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>
 * {@code\n * // Pin I/O thread to core 2\n * Thread ioThread = new Thread(() -> {\n *     NativeThread.pin(2);\n *     while (!stopped) {\n *         // I/O polling loop\n *     }\n * });\n * ioThread.start();\n * }
 * </pre>
 *
 * \n *\n *
 *
 * <h2>Platform Requirements</h2>
 *
 * \n *\n *
 *
 * <ul>
 *   \n *
 *   <li><b>Linux:</b> Uses {@code gettid()} and {@code sched_setaffinity()}\n *
 *   <li><b>Java:</b> Requires {@code --enable-native-access=ALL-UNNAMED}\n *
 *   <li><b>Permissions:</b> May require CAP_SYS_NICE for cross-user affinity\n *
 * </ul>
 *
 * \n *\n *
 *
 * <h2>Thread Safety</h2>
 *
 * \n *\n *
 *
 * <p>All methods are thread-safe and can be called from any thread.\n * {@link #pin(int)} should be
 * called from the thread being pinned.\n
 */
public final class NativeThread {

    /** Native linker for calling C functions. */
    private static final Linker LINKER = Linker.nativeLinker();

    /** Symbol lookup for standard library functions. */
    private static final SymbolLookup STDLIB = Linker.nativeLinker().defaultLookup();

    /**
     * Handle to gettid() - returns the kernel thread ID.
     *
     * <p>Signature: {@code pid_t gettid(void)}
     */
    private static final MethodHandle gettid;

    /**
     * Handle to sched_setaffinity() - sets CPU affinity mask.
     *
     * <p>Signature: {@code int sched_setaffinity(pid_t pid, size_t cpusetsize, cpu_set_t *mask)}
     */
    private static final MethodHandle sched_setaffinity;

    static {
        try {
            gettid =
                    LINKER.downcallHandle(
                            STDLIB.find("gettid")
                                    .orElseThrow(
                                            () -> new IllegalStateException("gettid not found")),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT));

            sched_setaffinity =
                    LINKER.downcallHandle(
                            STDLIB.find("sched_setaffinity")
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "sched_setaffinity not found")),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT, // pid_t pid
                                    ValueLayout.JAVA_LONG, // size_t cpusetsize
                                    ValueLayout.ADDRESS // cpu_set_t *mask
                                    ));
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException("Failed to initialize NativeThread", e);
        }
    }

    /** Private constructor - utility class. */
    private NativeThread() {}

    /**
     * Pins the current thread to the specified CPU core.
     *
     * <p>After this call, the thread will only execute on the specified core.\n * This is useful
     * for:\n *\n *
     *
     * <ul>
     *   \n *
     *   <li>I/O polling threads (reduces latency variance)\n *
     *   <li>SQPOLL kernel threads (dedicated CPU for polling)\n *
     *   <li>Latency-sensitive worker threads\n *
     * </ul>
     *
     * \n *\n *
     *
     * <p><b>Implementation:</b> Uses Linux {@code sched_setaffinity()} with\n * a cpu_set_t
     * bitmask. Currently supports CPUs 0-63 (single 64-bit word).\n *\n * @param cpuId the CPU core
     * ID (0-based, typically 0 to nproc-1)\n * @return {@code true} if successful, {@code false} on
     * error\n
     */
    public static boolean pin(int cpuId) {
        try {
            int tid = (int) gettid.invokeExact();

            // cpu_set_t on Linux is 1024 bits (128 bytes).
            // We allocate the full struct for correctness.
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment mask = arena.allocate(128); // 1024 bits = 128 bytes
                mask.fill((byte) 0);

                // cpu_set_t is an array of unsigned longs (64 bits each on x86_64).
                // Find which 64-bit word and which bit within that word.
                long wordIndex = cpuId / 64;
                long bitIndex = cpuId % 64;

                if (wordIndex * 8 >= 128) {
                    throw new IllegalArgumentException("CPU ID too large: " + cpuId);
                }

                // Set the bit for the target CPU
                long currentVal = mask.get(ValueLayout.JAVA_LONG, wordIndex * 8);
                mask.set(ValueLayout.JAVA_LONG, wordIndex * 8, currentVal | (1L << bitIndex));

                int ret = (int) sched_setaffinity.invokeExact(tid, (long) mask.byteSize(), mask);
                return ret == 0;
            }
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Returns the kernel thread ID of the current thread.
     *
     * <p>This is the Linux kernel's thread ID (TID), not the Java thread ID.\n * Useful for
     * debugging and native library interactions.\n *\n * @return the kernel thread ID\n * @throws
     * IllegalStateException if the syscall fails\n
     */
    public static int getCurrentThreadId() {
        try {
            return (int) gettid.invokeExact();
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to obtain native thread ID", e);
        }
    }
}

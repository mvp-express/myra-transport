package express.mvp.myra.server;

import express.mvp.myra.transport.TransportConfig;

/**
 * Configuration for a MyraServer instance.
 *
 * <p>This class uses the builder pattern to configure all aspects of the server, including network
 * binding, buffer pool settings, and io_uring-specific options like SQPOLL mode and CPU affinity.
 *
 * <h2>Configuration Categories</h2>
 *
 * <table border="1">
 *   <caption>Configuration options by category</caption>
 *   <tr><th>Category</th><th>Options</th><th>Description</th></tr>
 *   <tr><td>Network</td><td>host, port</td><td>Socket binding address</td></tr>
 *   <tr><td>Buffer Pool</td><td>numBuffers, bufferSize</td><td>Registered buffer configuration</td></tr>
 *   <tr><td>CPU Affinity</td><td>cpuAffinity, sqPollCpuAffinity</td><td>Core pinning for NUMA optimization</td></tr>
 *   <tr><td>SQPOLL</td><td>sqPollEnabled, sqPollIdleTimeout</td><td>Kernel-side SQ polling mode</td></tr>
 * </table>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * MyraServerConfig config = MyraServerConfig.builder()
 *     .host("0.0.0.0")
 *     .port(8080)
 *     .numBuffers(2048)
 *     .bufferSize(8192)
 *     .cpuAffinity(0)
 *     .sqPollEnabled(true)
 *     .sqPollCpuAffinity(1)
 *     .sqPollIdleTimeout(1000)
 *     .build();
 * }</pre>
 *
 * <h2>SQPOLL Mode Considerations</h2>
 *
 * <p>When SQPOLL is enabled, a dedicated kernel thread continuously polls the submission queue,
 * eliminating system call overhead for submitting I/O operations. This provides the lowest latency
 * but consumes CPU even when idle (until the idle timeout triggers). For best results:
 *
 * <ul>
 *   <li>Pin the SQPOLL thread to a dedicated core (sqPollCpuAffinity)
 *   <li>Set the server thread to a different core (cpuAffinity)
 *   <li>Tune sqPollIdleTimeout based on traffic patterns
 * </ul>
 *
 * @see MyraServer
 * @see express.mvp.myra.transport.TransportConfig
 */
public class MyraServerConfig {
    /** Host address to bind to (e.g., "0.0.0.0" or "127.0.0.1"). */
    private final String host;

    /** TCP port to listen on. */
    private final int port;

    /** Number of registered buffers in the pool. */
    private final int numBuffers;

    /** Size of each buffer in bytes. */
    private final int bufferSize;

    /** Transport backend type (io_uring or NIO). */
    private final TransportConfig.BackendType backendType;

    /** CPU core to pin the server thread to (-1 for no affinity). */
    private final int cpuAffinity;

    /** CPU core to pin the SQPOLL kernel thread to (-1 for no affinity). */
    private final int sqPollCpuAffinity;

    /** Whether to enable SQPOLL mode for reduced syscall overhead. */
    private final boolean sqPollEnabled;

    /** Idle timeout in microseconds before SQPOLL thread sleeps. */
    private final int sqPollIdleTimeout;

    /**
     * Creates a new configuration from a builder.
     *
     * @param builder the builder containing configuration values
     */
    private MyraServerConfig(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.numBuffers = builder.numBuffers;
        this.bufferSize = builder.bufferSize;
        this.backendType = builder.backendType;
        this.cpuAffinity = builder.cpuAffinity;
        this.sqPollCpuAffinity = builder.sqPollCpuAffinity;
        this.sqPollEnabled = builder.sqPollEnabled;
        this.sqPollIdleTimeout = builder.sqPollIdleTimeout;
    }

    /**
     * Returns the host address to bind to.
     *
     * @return the host address (e.g., "0.0.0.0" or "127.0.0.1")
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the TCP port to listen on.
     *
     * @return the port number (1-65535)
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the number of registered buffers in the pool.
     *
     * <p>This determines the maximum concurrent I/O operations that can use zero-copy buffers.
     * Should be sized based on expected concurrent connections and in-flight operations per
     * connection.
     *
     * @return the number of buffers
     */
    public int getNumBuffers() {
        return numBuffers;
    }

    /**
     * Returns the size of each buffer in bytes.
     *
     * <p>Should be sized to accommodate the largest expected message plus framing overhead. Common
     * values are 4096 (4KB) or 8192 (8KB).
     *
     * @return the buffer size in bytes
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Returns the transport backend type to use.
     *
     * @return the backend type (io_uring or NIO)
     */
    public TransportConfig.BackendType getBackendType() {
        return backendType;
    }

    /**
     * Returns the CPU core affinity for the server thread.
     *
     * <p>Setting CPU affinity pins the server thread to a specific core, improving cache locality
     * and reducing context switch overhead.
     *
     * @return the CPU core index, or -1 if no affinity is set
     */
    public int getCpuAffinity() {
        return cpuAffinity;
    }

    /**
     * Returns the CPU core affinity for the SQPOLL kernel thread.
     *
     * <p>When SQPOLL is enabled, the kernel creates a dedicated thread that continuously polls the
     * submission queue. Pinning this thread to a dedicated core prevents interference with the
     * server thread.
     *
     * @return the CPU core index, or -1 if no affinity is set
     */
    public int getSqPollCpuAffinity() {
        return sqPollCpuAffinity;
    }

    /**
     * Returns whether SQPOLL mode is enabled.
     *
     * <p>SQPOLL mode eliminates system call overhead by having a kernel thread continuously poll
     * the submission queue. This provides the lowest latency but uses CPU even when idle.
     *
     * @return true if SQPOLL mode is enabled
     * @see #getSqPollIdleTimeout()
     */
    public boolean isSqPollEnabled() {
        return sqPollEnabled;
    }

    /**
     * Returns the SQPOLL idle timeout in microseconds.
     *
     * <p>When SQPOLL is enabled and no new submissions are made within this timeout, the kernel
     * thread goes to sleep and will be woken by the next submission (requiring a system call).
     * Lower values keep the thread active longer; higher values reduce CPU usage during idle
     * periods.
     *
     * @return the idle timeout in microseconds
     */
    public int getSqPollIdleTimeout() {
        return sqPollIdleTimeout;
    }

    /**
     * Creates a new builder for constructing MyraServerConfig instances.
     *
     * @return a new builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating MyraServerConfig instances.
     *
     * <p>Provides a fluent API for configuring all server options with sensible defaults.
     *
     * <h2>Default Values</h2>
     *
 * <ul>
 *   <li>host: "0.0.0.0" (all interfaces)
 *   <li>port: 8080
 *   <li>numBuffers: 1024
 *   <li>bufferSize: 4096 bytes
 *   <li>backendType: IO_URING
 *   <li>cpuAffinity: -1 (no affinity)
 *   <li>sqPollCpuAffinity: -1 (no affinity)
 *   <li>sqPollEnabled: false
 *   <li>sqPollIdleTimeout: 2000 microseconds
 * </ul>
     */
    public static class Builder {
        private String host = "0.0.0.0";
        private int port = 8080;
        private int numBuffers = 1024;
        private int bufferSize = 4096;
        private TransportConfig.BackendType backendType = TransportConfig.BackendType.IO_URING;
        private int cpuAffinity = -1;
        private int sqPollCpuAffinity = -1;
        private boolean sqPollEnabled = false;
        private int sqPollIdleTimeout = 2000;

        /**
         * Sets the host address to bind to.
         *
         * @param host the host address (e.g., "0.0.0.0" for all interfaces)
         * @return this builder for method chaining
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets the TCP port to listen on.
         *
         * @param port the port number (1-65535)
         * @return this builder for method chaining
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets the number of registered buffers in the pool.
         *
         * @param numBuffers the number of buffers (should be power of 2 for efficiency)
         * @return this builder for method chaining
         */
        public Builder numBuffers(int numBuffers) {
            this.numBuffers = numBuffers;
            return this;
        }

        /**
         * Sets the size of each buffer in bytes.
         *
         * @param bufferSize the buffer size in bytes
         * @return this builder for method chaining
         */
        public Builder bufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
            return this;
        }

        /**
         * Sets the transport backend type.
         *
         * @param backendType the backend type to use
         * @return this builder
         */
        public Builder backendType(TransportConfig.BackendType backendType) {
            this.backendType = backendType;
            return this;
        }

        /**
         * Sets the CPU core affinity for the server thread.
         *
         * @param cpuAffinity the CPU core index, or -1 for no affinity
         * @return this builder for method chaining
         */
        public Builder cpuAffinity(int cpuAffinity) {
            this.cpuAffinity = cpuAffinity;
            return this;
        }

        /**
         * Sets the CPU core affinity for the SQPOLL kernel thread.
         *
         * @param sqPollCpuAffinity the CPU core index, or -1 for no affinity
         * @return this builder for method chaining
         */
        public Builder sqPollCpuAffinity(int sqPollCpuAffinity) {
            this.sqPollCpuAffinity = sqPollCpuAffinity;
            return this;
        }

        /**
         * Enables or disables SQPOLL mode.
         *
         * <p>When enabled, the kernel continuously polls the submission queue, eliminating system
         * call overhead at the cost of CPU usage.
         *
         * @param enabled true to enable SQPOLL mode
         * @return this builder for method chaining
         */
        public Builder sqPollEnabled(boolean enabled) {
            this.sqPollEnabled = enabled;
            return this;
        }

        /**
         * Sets the SQPOLL idle timeout.
         *
         * @param timeout the idle timeout in microseconds
         * @return this builder for method chaining
         */
        public Builder sqPollIdleTimeout(int timeout) {
            this.sqPollIdleTimeout = timeout;
            return this;
        }

        /**
         * Builds a new MyraServerConfig with the configured values.
         *
         * @return a new immutable configuration instance
         */
        public MyraServerConfig build() {
            return new MyraServerConfig(this);
        }
    }
}

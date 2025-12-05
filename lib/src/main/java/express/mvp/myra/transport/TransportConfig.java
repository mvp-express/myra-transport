package express.mvp.myra.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for transport creation and runtime behavior.
 *
 * <p>This immutable configuration object controls all aspects of transport initialization,
 * including backend selection, buffer pool sizing, and performance tuning parameters.
 *
 * <h2>Configuration Options</h2>
 *
 * <table border="1">
 *   <caption>Transport Configuration Parameters</caption>
 *   <tr><th>Parameter</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>backendType</td><td>IO_URING</td><td>I/O backend implementation</td></tr>
 *   <tr><td>numBuffers</td><td>256</td><td>Number of registered buffers</td></tr>
 *   <tr><td>bufferSize</td><td>64KB</td><td>Size of each buffer</td></tr>
 *   <tr><td>connectionTimeout</td><td>5s</td><td>TCP connection timeout</td></tr>
 *   <tr><td>sqPollEnabled</td><td>false</td><td>Enable SQPOLL kernel thread</td></tr>
 * </table>
 *
 * <h2>SQPOLL Mode</h2>
 *
 * <p>When {@code sqPollEnabled} is true, io_uring creates a dedicated kernel thread that polls for
 * submissions, eliminating syscall overhead entirely for steady-state I/O. This provides the lowest
 * latency but consumes a CPU core continuously.
 *
 * <h2>CPU Affinity</h2>
 *
 * <p>For latency-sensitive applications, use {@code cpuAffinity} to pin the I/O thread and {@code
 * sqPollCpuAffinity} to pin the SQPOLL kernel thread. Values of -1 disable affinity (OS scheduler
 * chooses).
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * TransportConfig config = TransportConfig.builder()
 *     .backendType(BackendType.IO_URING)
 *     .registeredBuffers(RegisteredBuffersConfig.builder()
 *         .numBuffers(512)
 *         .bufferSize(32768)
 *         .build())
 *     .connectionTimeout(Duration.ofSeconds(10))
 *     .sqPollEnabled(true)
 *     .sqPollCpuAffinity(3)
 *     .build();
 *
 * Transport transport = TransportFactory.create(config);
 * }</pre>
 *
 * \n *\n * @see TransportFactory\n * @see Transport\n
 */
public final class TransportConfig {

    /** The selected I/O backend implementation. */
    private final BackendType backendType;

    /** Configuration for registered buffer pool. */
    private final RegisteredBuffersConfig registeredBuffersConfig;

    /** TCP connection establishment timeout. */
    private final Duration connectionTimeout;

    /** CPU core to pin the I/O thread to (-1 for no affinity). */
    private final int cpuAffinity;

    /** CPU core to pin the SQPOLL kernel thread to (-1 for no affinity). */
    private final int sqPollCpuAffinity;

    /** Whether to enable SQPOLL mode for syscall-free I/O. */
    private final boolean sqPollEnabled;

    /** SQPOLL idle timeout in milliseconds before kernel thread sleeps. */
    private final int sqPollIdleTimeout;

    /**
     * Creates a new configuration from a builder.
     *
     * @param builder the builder containing configuration values
     */
    private TransportConfig(Builder builder) {
        this.backendType = builder.backendType;
        this.registeredBuffersConfig = builder.registeredBuffersConfig;
        this.connectionTimeout = builder.connectionTimeout;
        this.cpuAffinity = builder.cpuAffinity;
        this.sqPollCpuAffinity = builder.sqPollCpuAffinity;
        this.sqPollEnabled = builder.sqPollEnabled;
        this.sqPollIdleTimeout = builder.sqPollIdleTimeout;
    }

    /**
     * Creates a new builder for constructing configuration.
     *
     * @return a new builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the selected I/O backend type.
     *
     * @return the backend type (never null)
     */
    public BackendType backendType() {
        return backendType;
    }

    /**
     * Returns the registered buffer pool configuration.
     *
     * @return the buffer configuration (never null)
     */
    public RegisteredBuffersConfig registeredBuffersConfig() {
        return registeredBuffersConfig;
    }

    /**
     * Returns the TCP connection timeout.
     *
     * @return the connection timeout duration
     */
    public Duration connectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Returns the CPU affinity for the I/O thread.
     *
     * @return the CPU core ID, or -1 if no affinity is set
     */
    public int cpuAffinity() {
        return cpuAffinity;
    }

    /**
     * Returns the CPU affinity for the SQPOLL kernel thread.
     *
     * <p>Only relevant when {@link #sqPollEnabled()} is true.
     *
     * @return the CPU core ID, or -1 if no affinity is set
     */
    public int sqPollCpuAffinity() {
        return sqPollCpuAffinity;
    }

    /**
     * Returns whether SQPOLL mode is enabled.
     *
     * <p>When enabled, io_uring creates a dedicated kernel thread that continuously polls for
     * submissions, eliminating syscall overhead.\n *\n * @return {@code true} if SQPOLL is
     * enabled\n
     */
    public boolean sqPollEnabled() {
        return sqPollEnabled;
    }

    /**
     * Returns the SQPOLL idle timeout in milliseconds.
     *
     * <p>After this duration with no submissions, the kernel thread enters a sleep state to reduce
     * CPU usage during idle periods.
     *
     * @return the idle timeout in milliseconds
     */
    public int sqPollIdleTimeout() {
        return sqPollIdleTimeout;
    }

    /**
     * Available I/O backend implementations.
     *
     * <p>The backend determines the underlying I/O mechanism used for network operations. Different
     * backends have different platform requirements and performance characteristics.
     */
    public enum BackendType {
        /**
         * Linux io_uring backend (recommended for Linux).
         *
         * <p>Requires Linux kernel 5.1+ with liburing installed. Provides registered buffers, batch
         * submission, and optional SQPOLL.
         */
        IO_URING,

        /**
         * Java NIO backend (portable fallback).
         *
         * <p>Works on all platforms. Uses standard Java NIO with Selector. Does not support
         * registered buffers or batch submission.
         */
        NIO,

        /**
         * Linux XDP backend (future, experimental).
         *
         * <p>Kernel bypass via eBPF for ultra-low latency. Requires Linux kernel 4.8+ with
         * XDP-enabled NIC drivers.
         */
        XDP,

        /**
         * DPDK backend (future, experimental).
         *
         * <p>Full kernel bypass with poll-mode drivers. Requires DPDK-enabled NIC and dedicated CPU
         * cores.
         */
        DPDK
    }

    /**
     * Builder for constructing {@link TransportConfig} instances.
     *
     * <p>All parameters have sensible defaults. Only override what you need.\n
     */
    public static final class Builder {
        private BackendType backendType = BackendType.IO_URING;
        private RegisteredBuffersConfig registeredBuffersConfig =
                RegisteredBuffersConfig.defaults();
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private int cpuAffinity = -1;
        private int sqPollCpuAffinity = -1;
        private boolean sqPollEnabled = false;
        private int sqPollIdleTimeout = 2000; // 2 seconds default

        /**
         * Sets the I/O backend type.
         *
         * @param backendType the backend to use
         * @return this builder for chaining
         * @throws NullPointerException if backendType is null
         */
        public Builder backendType(BackendType backendType) {
            this.backendType = Objects.requireNonNull(backendType);
            return this;
        }

        /**
         * Sets the registered buffer pool configuration.
         *
         * @param config the buffer configuration
         * @return this builder for chaining
         * @throws NullPointerException if config is null
         */
        public Builder registeredBuffers(RegisteredBuffersConfig config) {
            this.registeredBuffersConfig = Objects.requireNonNull(config);
            return this;
        }

        /**
         * Sets the TCP connection timeout.
         *
         * @param timeout the connection timeout
         * @return this builder for chaining
         * @throws NullPointerException if timeout is null
         */
        public Builder connectionTimeout(Duration timeout) {
            this.connectionTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /**
         * Sets the CPU affinity for the I/O thread.
         *
         * @param cpuId the CPU core to pin to, or -1 for no affinity
         * @return this builder for chaining
         */
        public Builder cpuAffinity(int cpuId) {
            this.cpuAffinity = cpuId;
            return this;
        }

        /**
         * Sets the CPU affinity for the SQPOLL kernel thread.
         *
         * <p>Only used when {@link #sqPollEnabled(boolean)} is true.
         *
         * @param cpuId the CPU core to pin to, or -1 for no affinity
         * @return this builder for chaining
         */
        public Builder sqPollCpuAffinity(int cpuId) {
            this.sqPollCpuAffinity = cpuId;
            return this;
        }

        /**
         * Enables or disables SQPOLL mode.
         *
         * <p>SQPOLL creates a dedicated kernel thread for submission polling, eliminating syscall
         * overhead. Uses one CPU core continuously.
         *
         * @param enabled true to enable SQPOLL
         * @return this builder for chaining
         */
        public Builder sqPollEnabled(boolean enabled) {
            this.sqPollEnabled = enabled;
            return this;
        }

        /**
         * Sets the SQPOLL idle timeout.
         *
         * @param millis idle timeout in milliseconds before kernel thread sleeps
         * @return this builder for chaining
         */
        public Builder sqPollIdleTimeout(int millis) {
            this.sqPollIdleTimeout = millis;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return a new immutable TransportConfig
         */
        public TransportConfig build() {
            return new TransportConfig(this);
        }
    }

    /**
     * Configuration for the registered buffer pool.
     *
     * <p>Controls the number and size of pre-registered buffers used for zero-copy I/O.
     *
     * <h3>Sizing Guidelines</h3>
     *
     * <ul>
     *   <li><b>numBuffers:</b> Should match expected concurrent I/O operations. Rule of thumb: 2x
     *       expected concurrent connections.
     *   <li><b>bufferSize:</b> Should match typical message size plus overhead. Common values:
     *       8KB-64KB depending on protocol.
     * </ul>
     *
     * <h3>Memory Usage</h3>
     *
     * <p>Total memory = numBuffers × (bufferSize rounded up to page boundary)
     *
     * @see RegisteredBufferPool
     */
    public static final class RegisteredBuffersConfig {
        /** Whether registered buffers are enabled. */
        private final boolean enabled;

        /** Number of buffers in the pool. */
        private final int numBuffers;

        /** Size of each buffer in bytes (will be page-aligned). */
        private final int bufferSize;

        /**
         * Creates a new configuration from a builder.
         *
         * @param builder the builder containing configuration values
         */
        private RegisteredBuffersConfig(Builder builder) {
            this.enabled = builder.enabled;
            this.numBuffers = builder.numBuffers;
            this.bufferSize = builder.bufferSize;
        }

        /**
         * Creates a new builder for constructing configuration.
         *
         * @return a new builder with default values
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns a configuration with default values.
         *
         * @return default configuration (256 buffers × 64KB each)
         */
        public static RegisteredBuffersConfig defaults() {
            return builder().build();
        }

        /**
         * Returns whether registered buffers are enabled.
         *
         * @return {@code true} if enabled
         */
        public boolean enabled() {
            return enabled;
        }

        /**
         * Returns the number of buffers in the pool.
         *
         * @return the buffer count
         */
        public int numBuffers() {
            return numBuffers;
        }

        /**
         * Returns the size of each buffer in bytes.
         *
         * <p>Note: Actual size may be larger due to page alignment.
         *
         * @return the buffer size in bytes
         */
        public int bufferSize() {
            return bufferSize;
        }

        /** Builder for constructing {@link RegisteredBuffersConfig} instances. */
        public static final class Builder {
            private boolean enabled = true;
            private int numBuffers = 256;
            private int bufferSize = 65536; // 64KB

            /**
             * Enables or disables registered buffers.
             *
             * @param enabled true to enable
             * @return this builder for chaining
             */
            public Builder enabled(boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            /**
             * Sets the number of buffers in the pool.
             *
             * @param num the buffer count (must be positive)
             * @return this builder for chaining
             * @throws IllegalArgumentException if num is not positive
             */
            public Builder numBuffers(int num) {
                if (num <= 0) throw new IllegalArgumentException("numBuffers must be positive");
                this.numBuffers = num;
                return this;
            }

            /**
             * Sets the size of each buffer.
             *
             * @param size the buffer size in bytes (must be positive)
             * @return this builder for chaining
             * @throws IllegalArgumentException if size is not positive
             */
            public Builder bufferSize(int size) {
                if (size <= 0) throw new IllegalArgumentException("bufferSize must be positive");
                this.bufferSize = size;
                return this;
            }

            /**
             * Builds the configuration.
             *
             * @return a new immutable RegisteredBuffersConfig
             */
            public RegisteredBuffersConfig build() {
                return new RegisteredBuffersConfig(this);
            }
        }
    }
}

package express.mvp.myra.transport;

/**
 * Statistics for I/O backend operations.
 *
 * <p>This immutable class provides a snapshot of backend performance metrics, including operation
 * counts, throughput, and syscall efficiency.
 *
 * <h2>Key Metrics</h2>
 *
 * <table border="1">
 *   <caption>Backend Statistics</caption>
 *   <tr><th>Metric</th><th>Description</th><th>Ideal Value</th></tr>
 *   <tr><td>syscallReductionRatio</td><td>Operations per syscall</td><td>&gt; 50x</td></tr>
 *   <tr><td>avgBatchSize</td><td>Average operations per batch</td><td>32-128</td></tr>
 *   <tr><td>failedSends/Receives</td><td>Error counts</td><td>0</td></tr>
 * </table>
 *
 * <h2>Syscall Reduction</h2>
 *
 * <p>The {@link #getSyscallReductionRatio()} method returns the ratio of total operations to
 * syscalls. With io_uring batch submission, this should typically be 50-100x, meaning 50-100 I/O
 * operations per syscall.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * BackendStats stats = backend.getStats();
 *
 * System.out.println("Send/Receive: " + stats.getTotalSends()
 *     + "/" + stats.getTotalReceives());
 * System.out.println("Throughput: " + stats.getTotalBytesSent() + " bytes sent");
 * System.out.println("Syscall reduction: "
 *     + String.format("%.1fx", stats.getSyscallReductionRatio()));
 * }</pre>
 *
 * @see TransportBackend#getStats()
 */
public final class BackendStats {

    /** Total number of send operations completed. */
    private final long totalSends;

    /** Total number of receive operations completed. */
    private final long totalReceives;

    /** Cumulative bytes sent. */
    private final long totalBytesSent;

    /** Cumulative bytes received. */
    private final long totalBytesReceived;

    /** Number of send operations that failed. */
    private final long failedSends;

    /** Number of receive operations that failed. */
    private final long failedReceives;

    /** Number of batch submissions (io_uring_submit calls). */
    private final long batchSubmissions;

    /** Average number of operations per batch. */
    private final double avgBatchSize;

    /** Total number of syscalls made. */
    private final long totalSyscalls;

    /**
     * Creates a new stats snapshot from a builder.
     *
     * @param builder the builder containing statistics
     */
    private BackendStats(Builder builder) {
        this.totalSends = builder.totalSends;
        this.totalReceives = builder.totalReceives;
        this.totalBytesSent = builder.totalBytesSent;
        this.totalBytesReceived = builder.totalBytesReceived;
        this.failedSends = builder.failedSends;
        this.failedReceives = builder.failedReceives;
        this.batchSubmissions = builder.batchSubmissions;
        this.avgBatchSize = builder.avgBatchSize;
        this.totalSyscalls = builder.totalSyscalls;
    }

    /**
     * Creates a new builder for constructing stats.
     *
     * @return a new builder with zero values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the total number of completed send operations.
     *
     * @return send operation count
     */
    public long getTotalSends() {
        return totalSends;
    }

    /**
     * Returns the total number of completed receive operations.
     *
     * @return receive operation count
     */
    public long getTotalReceives() {
        return totalReceives;
    }

    /**
     * Returns the cumulative bytes sent.
     *
     * @return bytes sent
     */
    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Returns the cumulative bytes received.
     *
     * @return bytes received
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }

    /**
     * Returns the number of failed send operations.
     *
     * @return failed send count
     */
    public long getFailedSends() {
        return failedSends;
    }

    /**
     * Returns the number of failed receive operations.
     *
     * @return failed receive count
     */
    public long getFailedReceives() {
        return failedReceives;
    }

    /**
     * Returns the number of batch submissions.
     *
     * <p>For io_uring, this is the number of {@code io_uring_submit()} calls.
     *
     * @return batch submission count
     */
    public long getBatchSubmissions() {
        return batchSubmissions;
    }

    /**
     * Returns the average number of operations per batch.
     *
     * <p>Higher values indicate more efficient batching.
     *
     * @return average batch size
     */
    public double getAvgBatchSize() {
        return avgBatchSize;
    }

    /**
     * Returns the total number of syscalls made.
     *
     * @return syscall count
     */
    public long getTotalSyscalls() {
        return totalSyscalls;
    }

    /**
     * Calculates the syscall reduction ratio.
     *
     * <p>This is the ratio of total operations (sends + receives) to syscalls. With effective
     * batching, this should be 50-100x.
     *
     * <p><b>Example:</b> If 10,000 operations required 100 syscalls, the ratio is 100x (100-fold
     * syscall reduction).
     *
     * @return the reduction ratio, or 0 if no syscalls have been made
     */
    public double getSyscallReductionRatio() {
        if (totalSyscalls == 0) return 0.0;
        long totalOps = totalSends + totalReceives;
        return (double) totalOps / totalSyscalls;
    }

    /** Builder for constructing {@link BackendStats} instances. */
    public static final class Builder {
        private long totalSends = 0;
        private long totalReceives = 0;
        private long totalBytesSent = 0;
        private long totalBytesReceived = 0;
        private long failedSends = 0;
        private long failedReceives = 0;
        private long batchSubmissions = 0;
        private double avgBatchSize = 0.0;
        private long totalSyscalls = 0;

        /**
         * Sets the total send count.
         *
         * @param count send operations completed
         * @return this builder for chaining
         */
        public Builder totalSends(long count) {
            this.totalSends = count;
            return this;
        }

        /**
         * Sets the total receive count.
         *
         * @param count receive operations completed
         * @return this builder for chaining
         */
        public Builder totalReceives(long count) {
            this.totalReceives = count;
            return this;
        }

        /**
         * Sets the total bytes sent.
         *
         * @param bytes cumulative bytes sent
         * @return this builder for chaining
         */
        public Builder totalBytesSent(long bytes) {
            this.totalBytesSent = bytes;
            return this;
        }

        /**
         * Sets the total bytes received.
         *
         * @param bytes cumulative bytes received
         * @return this builder for chaining
         */
        public Builder totalBytesReceived(long bytes) {
            this.totalBytesReceived = bytes;
            return this;
        }

        /**
         * Sets the failed send count.
         *
         * @param count failed send operations
         * @return this builder for chaining
         */
        public Builder failedSends(long count) {
            this.failedSends = count;
            return this;
        }

        /**
         * Sets the failed receive count.
         *
         * @param count failed receive operations
         * @return this builder for chaining
         */
        public Builder failedReceives(long count) {
            this.failedReceives = count;
            return this;
        }

        /**
         * Sets the batch submission count.
         *
         * @param count number of batch submissions
         * @return this builder for chaining
         */
        public Builder batchSubmissions(long count) {
            this.batchSubmissions = count;
            return this;
        }

        /**
         * Sets the average batch size.
         *
         * @param avg average operations per batch
         * @return this builder for chaining
         */
        public Builder avgBatchSize(double avg) {
            this.avgBatchSize = avg;
            return this;
        }

        /**
         * Sets the total syscall count.
         *
         * @param count number of syscalls made
         * @return this builder for chaining
         */
        public Builder totalSyscalls(long count) {
            this.totalSyscalls = count;
            return this;
        }

        /**
         * Builds the statistics snapshot.
         *
         * @return a new immutable BackendStats
         */
        public BackendStats build() {
            return new BackendStats(this);
        }
    }
}

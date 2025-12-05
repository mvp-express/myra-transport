package express.mvp.myra.transport.buffer;

/**
 * Immutable snapshot of buffer pool performance metrics.
 *
 * <p>This record captures acquisition statistics and pool state at a point in time,
 * useful for monitoring, debugging, and performance tuning.
 *
 * <h2>Metric Categories</h2>
 *
 * <ul>
 *   <li><b>Acquisition counts:</b> Track success/failure rates
 *   <li><b>Wait times:</b> Measure contention and timeout behavior
 *   <li><b>Pool state:</b> Current availability and capacity
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * TimedBufferPool pool = new TimedBufferPool(256, 65536);
 * // ... use pool ...
 *
 * BufferPoolMetrics metrics = pool.metrics();
 * System.out.printf("Success rate: %.2f%%%n",
 *     100.0 * metrics.successfulAcquisitions() / metrics.totalAcquisitions());
 * System.out.printf("Avg wait: %.2f ms%n",
 *     metrics.avgWaitTimeNanos() / 1_000_000.0);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This record is immutable and safe to share across threads. However, the metrics
 * represent a snapshot and may be stale immediately after retrieval.
 *
 * @param totalAcquisitions total number of acquisition attempts (successful + failed)
 * @param successfulAcquisitions number of acquisitions that succeeded
 * @param failedAcquisitions number of acquisitions that timed out or failed
 * @param avgWaitTimeNanos average wait time in nanoseconds for successful acquisitions
 * @param maxWaitTimeNanos maximum wait time observed in nanoseconds
 * @param currentAvailable number of buffers currently available in the pool
 * @param poolSize total capacity of the pool
 * @see TimedBufferPool
 */
public record BufferPoolMetrics(
        long totalAcquisitions,
        long successfulAcquisitions,
        long failedAcquisitions,
        long avgWaitTimeNanos,
        long maxWaitTimeNanos,
        int currentAvailable,
        int poolSize) {

    /**
     * Returns the success rate as a ratio between 0.0 and 1.0.
     *
     * @return success rate (0.0 if no acquisitions attempted)
     */
    public double successRate() {
        return totalAcquisitions == 0 ? 0.0 : (double) successfulAcquisitions / totalAcquisitions;
    }

    /**
     * Returns the failure rate as a ratio between 0.0 and 1.0.
     *
     * @return failure rate (0.0 if no acquisitions attempted)
     */
    public double failureRate() {
        return totalAcquisitions == 0 ? 0.0 : (double) failedAcquisitions / totalAcquisitions;
    }

    /**
     * Returns the pool utilization as a ratio between 0.0 and 1.0.
     *
     * <p>Higher values indicate more buffers are in use.
     *
     * @return utilization ratio (1.0 - available/poolSize)
     */
    public double utilization() {
        return poolSize == 0 ? 0.0 : 1.0 - (double) currentAvailable / poolSize;
    }

    /**
     * Returns the average wait time in milliseconds.
     *
     * @return average wait time in milliseconds
     */
    public double avgWaitTimeMillis() {
        return avgWaitTimeNanos / 1_000_000.0;
    }

    /**
     * Returns the maximum wait time in milliseconds.
     *
     * @return maximum wait time in milliseconds
     */
    public double maxWaitTimeMillis() {
        return maxWaitTimeNanos / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format(
                "BufferPoolMetrics[total=%d, success=%d, failed=%d, avgWait=%.3fms, maxWait=%.3fms, available=%d/%d, utilization=%.1f%%]",
                totalAcquisitions,
                successfulAcquisitions,
                failedAcquisitions,
                avgWaitTimeMillis(),
                maxWaitTimeMillis(),
                currentAvailable,
                poolSize,
                utilization() * 100);
    }
}

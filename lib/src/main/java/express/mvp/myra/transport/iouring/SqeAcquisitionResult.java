package express.mvp.myra.transport.iouring;

import java.lang.foreign.MemorySegment;

/**
 * Result of attempting to acquire a Submission Queue Entry (SQE) from io_uring.
 *
 * <p>This record encapsulates the outcome of calling {@link LibUring#getSqe} with retry logic. It
 * provides a type-safe way to handle the three possible outcomes: success (SQE acquired), queue
 * full (retry after submit), or fatal error.
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * SqeAcquisitionResult result = acquireSqeWithRetry(3);
 * if (result.success()) {
 *     MemorySegment sqe = result.sqe();
 *     LibUring.prepSend(sqe, fd, buffer, length, 0);
 *     LibUring.sqeSetUserData(sqe, token);
 * } else if (result.shouldRetry()) {
 *     // Queue full - operations queued but need to submit and retry
 *     throw new TransportException("Submission queue overflow");
 * } else {
 *     // Fatal error - ring may be corrupted
 *     throw new TransportException("Failed to acquire SQE");
 * }
 * }</pre>
 *
 * <h2>Design Rationale</h2>
 *
 * <p>Using a record instead of exceptions for queue-full conditions avoids exception allocation on
 * the hot path. The queue-full case is a normal operational condition in high-throughput scenarios,
 * not an exceptional error.
 *
 * @param sqe the acquired SQE memory segment, or {@code null} if acquisition failed
 * @param success {@code true} if an SQE was successfully acquired
 * @param shouldRetry {@code true} if the caller should submit pending operations and retry
 * @see LibUring#getSqe(MemorySegment)
 * @see IoUringBackend#submitBatch()
 */
record SqeAcquisitionResult(MemorySegment sqe, boolean success, boolean shouldRetry) {

    /**
     * Creates a successful acquisition result.
     *
     * @param sqe the acquired SQE memory segment (must not be null)
     * @return a result indicating successful SQE acquisition
     */
    static SqeAcquisitionResult success(MemorySegment sqe) {
        return new SqeAcquisitionResult(sqe, true, false);
    }

    /**
     * Creates a queue-full result indicating retry is possible.
     *
     * <p>The caller should call {@link IoUringBackend#submitBatch()} to flush pending operations to
     * the kernel, then retry the SQE acquisition.
     *
     * @return a result indicating the queue is full but retry may succeed
     */
    static SqeAcquisitionResult queueFull() {
        return new SqeAcquisitionResult(null, false, true);
    }

    /**
     * Creates a fatal error result indicating retry will not help.
     *
     * <p>This typically indicates the io_uring ring is in an invalid state or has been closed. The
     * backend should be closed and recreated.
     *
     * @return a result indicating a fatal error occurred
     */
    static SqeAcquisitionResult fatal() {
        return new SqeAcquisitionResult(null, false, false);
    }
}

package express.mvp.myra.transport.framing;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A framing handler that uses a 4-byte big-endian length prefix.
 *
 * <p>This implementation frames messages by prepending a 4-byte (32-bit) unsigned integer in
 * network byte order (big-endian) that specifies the payload length. This is one of the most
 * common framing strategies used in network protocols.
 *
 * <h2>Frame Format</h2>
 *
 * <pre>
 * ┌────────────────────────┬─────────────────────────────────────┐
 * │  Length (4 bytes, BE)  │           Payload (N bytes)         │
 * └────────────────────────┴─────────────────────────────────────┘
 *           ▲                              ▲
 *           │                              │
 *     Network byte order            Variable length
 *     (big-endian)                  (0 to maxPayloadSize)
 * </pre>
 *
 * <h2>Configuration</h2>
 *
 * <p>The maximum payload size is configurable at construction time. The default is 16 MB
 * ({@value #DEFAULT_MAX_PAYLOAD_SIZE} bytes), which is suitable for most applications. For
 * protocols with smaller messages, a lower limit reduces memory requirements and provides
 * better protection against malformed length prefixes.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create with default max size (16 MB)
 * FramingHandler framing = new LengthPrefixedFramingHandler();
 *
 * // Or with custom max size (1 MB)
 * FramingHandler framing = new LengthPrefixedFramingHandler(1024 * 1024);
 *
 * // Frame a message
 * byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
 * MemorySegment source = MemorySegment.ofArray(payload);
 * MemorySegment frame = Arena.global().allocate(payload.length + framing.getHeaderSize());
 * int frameLength = framing.frameMessage(source, payload.length, frame);
 *
 * // Deframe a message
 * MemorySegment received = ...; // from network
 * MemorySegment output = Arena.global().allocate(framing.getMaxPayloadSize());
 * int payloadLength = framing.deframeMessage(received, bytesReceived, output);
 * if (payloadLength >= 0) {
 *     // Process complete message
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This class is immutable and thread-safe. The same instance can be used concurrently by
 * multiple threads for framing and deframing operations. All configuration is fixed at
 * construction time.
 *
 * <h2>Zero-Copy Operation</h2>
 *
 * <p>This implementation uses {@link MemorySegment#copy(MemorySegment, long, MemorySegment, long, long)}
 * for efficient data transfer between segments. No intermediate buffers are allocated during
 * framing or deframing operations.
 *
 * @see FramingHandler
 * @see FramingException
 */
public final class LengthPrefixedFramingHandler implements FramingHandler {

    /**
     * The size of the length prefix header in bytes.
     */
    public static final int HEADER_SIZE = 4;

    /**
     * The default maximum payload size: 16 MB.
     */
    public static final int DEFAULT_MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    /**
     * Layout for reading/writing the 4-byte big-endian length prefix.
     */
    private static final ValueLayout.OfInt LENGTH_LAYOUT =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN);

    private final int maxPayloadSize;

    /**
     * Creates a new length-prefixed framing handler with the default maximum payload size.
     *
     * <p>The default maximum payload size is {@value #DEFAULT_MAX_PAYLOAD_SIZE} bytes (16 MB).
     */
    public LengthPrefixedFramingHandler() {
        this(DEFAULT_MAX_PAYLOAD_SIZE);
    }

    /**
     * Creates a new length-prefixed framing handler with the specified maximum payload size.
     *
     * @param maxPayloadSize the maximum payload size in bytes
     * @throws IllegalArgumentException if maxPayloadSize is not positive or exceeds
     *         {@link Integer#MAX_VALUE} - {@link #HEADER_SIZE}
     */
    public LengthPrefixedFramingHandler(int maxPayloadSize) {
        if (maxPayloadSize <= 0) {
            throw new IllegalArgumentException("maxPayloadSize must be positive: " + maxPayloadSize);
        }
        // Ensure total frame size doesn't overflow
        if (maxPayloadSize > Integer.MAX_VALUE - HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "maxPayloadSize too large, would overflow frame size: " + maxPayloadSize);
        }
        this.maxPayloadSize = maxPayloadSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation writes a 4-byte big-endian length prefix followed by the payload.
     * The length prefix contains the payload size (not including the header itself).
     *
     * @throws FramingException if sourceLength exceeds {@link #getMaxPayloadSize()}
     */
    @Override
    public int frameMessage(MemorySegment source, int sourceLength, MemorySegment destination) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        if (sourceLength < 0) {
            throw new IllegalArgumentException("sourceLength must not be negative: " + sourceLength);
        }

        if (sourceLength > maxPayloadSize) {
            throw new FramingException(String.format(
                    "Payload size %d exceeds maximum allowed size %d", sourceLength, maxPayloadSize));
        }

        int frameLength = HEADER_SIZE + sourceLength;

        // Check destination capacity
        if (destination.byteSize() < frameLength) {
            throw new IndexOutOfBoundsException(String.format(
                    "Destination capacity %d is insufficient for frame size %d",
                    destination.byteSize(), frameLength));
        }

        // Write length prefix (big-endian)
        destination.set(LENGTH_LAYOUT, 0, sourceLength);

        // Copy payload
        if (sourceLength > 0) {
            MemorySegment.copy(source, 0, destination, HEADER_SIZE, sourceLength);
        }

        return frameLength;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation reads the 4-byte big-endian length prefix to determine the payload
     * size, then copies the payload to the destination. Returns -1 if the source doesn't contain
     * enough bytes for the complete frame (header + indicated payload length).
     *
     * @throws FramingException if the length prefix indicates a payload larger than
     *         {@link #getMaxPayloadSize()} or is negative
     */
    @Override
    public int deframeMessage(MemorySegment source, int sourceLength, MemorySegment destination) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");

        if (sourceLength < 0) {
            throw new IllegalArgumentException("sourceLength must not be negative: " + sourceLength);
        }

        // Need at least the header to read the length
        if (sourceLength < HEADER_SIZE) {
            return -1; // Incomplete header
        }

        // Read length prefix
        int payloadLength = source.get(LENGTH_LAYOUT, 0);

        // Validate length prefix
        if (payloadLength < 0) {
            throw new FramingException("Invalid negative length prefix: " + payloadLength);
        }

        if (payloadLength > maxPayloadSize) {
            throw new FramingException(String.format(
                    "Length prefix %d exceeds maximum allowed size %d", payloadLength, maxPayloadSize));
        }

        // Check if we have the complete frame
        int totalFrameLength = HEADER_SIZE + payloadLength;
        if (sourceLength < totalFrameLength) {
            return -1; // Incomplete payload
        }

        // Check destination capacity
        if (payloadLength > 0 && destination.byteSize() < payloadLength) {
            throw new IndexOutOfBoundsException(String.format(
                    "Destination capacity %d is insufficient for payload size %d",
                    destination.byteSize(), payloadLength));
        }

        // Copy payload to destination
        if (payloadLength > 0) {
            MemorySegment.copy(source, HEADER_SIZE, destination, 0, payloadLength);
        }

        return payloadLength;
    }

    /**
     * {@inheritDoc}
     *
     * @return 4 (the size of the 32-bit length prefix)
     */
    @Override
    public int getHeaderSize() {
        return HEADER_SIZE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    /**
     * Returns a string representation of this framing handler.
     *
     * @return a string containing the handler type and configuration
     */
    @Override
    public String toString() {
        return String.format("LengthPrefixedFramingHandler[headerSize=%d, maxPayloadSize=%d]",
                HEADER_SIZE, maxPayloadSize);
    }
}

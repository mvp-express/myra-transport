package express.mvp.myra.transport.framing;

import java.lang.foreign.MemorySegment;

/**
 * Strategy interface for message framing and deframing in network protocols.
 *
 * <p>Message framing is essential for reliable message delivery over stream-based protocols like
 * TCP, which provide a continuous byte stream without inherent message boundaries. This interface
 * defines the contract for framing strategies that encode message boundaries into the byte stream.
 *
 * <h2>Framing Process</h2>
 *
 * <pre>
 * Sender:                                    Receiver:
 * ┌──────────────┐                          ┌──────────────┐
 * │   Message    │                          │   Message    │
 * │   Payload    │                          │   Payload    │
 * └──────────────┘                          └──────────────┘
 *        │                                         ▲
 *        ▼ frameMessage()                          │ deframeMessage()
 * ┌────┬──────────────┐    Network    ┌────┬──────────────┐
 * │Hdr │   Payload    │  ─────────▶  │Hdr │   Payload    │
 * └────┴──────────────┘               └────┴──────────────┘
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * FramingHandler framing = new LengthPrefixedFramingHandler();
 *
 * // Frame a message for sending
 * MemorySegment payload = ...;
 * MemorySegment frame = allocator.allocate(payload.byteSize() + framing.getHeaderSize());
 * int frameLength = framing.frameMessage(payload, (int) payload.byteSize(), frame);
 *
 * // Deframe a received message
 * MemorySegment received = ...;
 * MemorySegment output = allocator.allocate(framing.getMaxPayloadSize());
 * int payloadLength = framing.deframeMessage(received, bytesReceived, output);
 * if (payloadLength >= 0) {
 *     // Complete message received
 *     processPayload(output.asSlice(0, payloadLength));
 * } else {
 *     // Incomplete frame, need more data
 *     waitForMoreData();
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations must be thread-safe. The framing and deframing operations should be stateless,
 * allowing the same handler instance to be used concurrently by multiple threads. Any configuration
 * (such as max message size) should be immutable after construction.
 *
 * <h2>Zero-Copy Design</h2>
 *
 * <p>This interface is designed for zero-copy operation using {@link MemorySegment}.
 * Implementations should avoid unnecessary data copying by writing directly to the destination
 * segment.
 *
 * @see LengthPrefixedFramingHandler
 * @see FramingException
 */
public interface FramingHandler {

    /**
     * Frames a message by adding protocol-specific headers to the payload.
     *
     * <p>This method writes the framing header followed by the payload data to the destination
     * segment. The destination must have sufficient capacity to hold the entire frame (header +
     * payload).
     *
     * <p><b>Required destination capacity:</b> {@code sourceLength + getHeaderSize()}
     *
     * @param source the source segment containing the message payload
     * @param sourceLength the number of bytes to read from the source (payload length)
     * @param destination the destination segment to write the framed message to
     * @return the total frame length (header + payload), which equals {@code sourceLength +
     *     getHeaderSize()}
     * @throws FramingException if the payload exceeds {@link #getMaxPayloadSize()}
     * @throws IndexOutOfBoundsException if destination has insufficient capacity
     * @throws NullPointerException if source or destination is null
     */
    int frameMessage(MemorySegment source, int sourceLength, MemorySegment destination);

    /**
     * Deframes a message by extracting the payload from a framed message.
     *
     * <p>This method reads the framing header to determine the payload length, validates the frame,
     * and copies the payload to the destination segment. If the source contains an incomplete frame
     * (fewer bytes than indicated by the header), this method returns -1.
     *
     * <h3>Return Values</h3>
     *
     * <ul>
     *   <li>{@code >= 0}: The payload length; the complete payload has been written to destination
     *   <li>{@code -1}: Incomplete frame; more data is needed to complete deframing
     * </ul>
     *
     * <p><b>Note:</b> When returning -1, the destination segment contents are undefined and should
     * not be used.
     *
     * @param source the source segment containing the framed message (header + payload)
     * @param sourceLength the number of bytes available in the source
     * @param destination the destination segment to write the extracted payload to
     * @return the payload length if deframing succeeded, or -1 if the frame is incomplete
     * @throws FramingException if the frame header indicates an invalid or oversized payload
     * @throws IndexOutOfBoundsException if destination has insufficient capacity for the payload
     * @throws NullPointerException if source or destination is null
     */
    int deframeMessage(MemorySegment source, int sourceLength, MemorySegment destination);

    /**
     * Returns the size of the framing header in bytes.
     *
     * <p>This is the overhead added to each message by the framing protocol. For length-prefixed
     * framing, this is typically 4 bytes (32-bit length field).
     *
     * @return the header size in bytes
     */
    int getHeaderSize();

    /**
     * Returns the maximum payload size that can be framed.
     *
     * <p>Attempts to frame messages larger than this size will result in a {@link
     * FramingException}. This limit is typically configured at construction time and may be
     * constrained by protocol requirements or memory considerations.
     *
     * @return the maximum payload size in bytes
     */
    int getMaxPayloadSize();
}

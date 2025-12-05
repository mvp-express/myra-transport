/**
 * Message framing utilities for network protocols.
 *
 * <p>This package provides abstractions and implementations for message framing, which is the
 * process of delimiting messages in a byte stream. Framing is essential for reliable communication
 * over stream-based protocols like TCP, which provide a continuous byte stream without inherent
 * message boundaries.
 *
 * <h2>Key Components</h2>
 *
 * <ul>
 *   <li>{@link express.mvp.myra.transport.framing.FramingHandler} - Strategy interface for
 *       framing/deframing
 *   <li>{@link express.mvp.myra.transport.framing.LengthPrefixedFramingHandler} - 4-byte
 *       big-endian length prefix implementation
 *   <li>{@link express.mvp.myra.transport.framing.FramingException} - Exception for framing errors
 * </ul>
 *
 * <h2>Framing Strategies</h2>
 *
 * <p>Common framing strategies include:
 *
 * <ul>
 *   <li><b>Length-prefixed:</b> Each message is preceded by its length (implemented here)
 *   <li><b>Delimiter-based:</b> Messages are separated by special byte sequences
 *   <li><b>Fixed-length:</b> All messages have the same size
 * </ul>
 *
 * <p>This package currently provides length-prefixed framing, which is widely used in RPC
 * protocols and binary formats due to its efficiency and simplicity.
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * import express.mvp.myra.transport.framing.*;
 * import java.lang.foreign.*;
 *
 * // Create a framing handler
 * FramingHandler framing = new LengthPrefixedFramingHandler();
 *
 * // Frame a message for sending
 * try (Arena arena = Arena.ofConfined()) {
 *     byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
 *     MemorySegment source = MemorySegment.ofArray(payload);
 *     MemorySegment frame = arena.allocate(payload.length + framing.getHeaderSize());
 *
 *     int frameLength = framing.frameMessage(source, payload.length, frame);
 *     sendToNetwork(frame.asSlice(0, frameLength));
 * }
 *
 * // Deframe a received message
 * try (Arena arena = Arena.ofConfined()) {
 *     MemorySegment received = receiveFromNetwork();
 *     MemorySegment output = arena.allocate(framing.getMaxPayloadSize());
 *
 *     int payloadLength = framing.deframeMessage(received, (int) received.byteSize(), output);
 *     if (payloadLength >= 0) {
 *         processPayload(output.asSlice(0, payloadLength));
 *     } else {
 *         // Need more data for complete frame
 *         bufferPartialData(received);
 *     }
 * }
 * }</pre>
 *
 * @see express.mvp.myra.transport.framing.FramingHandler
 * @see express.mvp.myra.transport.framing.LengthPrefixedFramingHandler
 */
package express.mvp.myra.transport.framing;

package express.mvp.myra.transport.framing;

/**
 * Exception thrown when message framing or deframing fails.
 *
 * <p>This exception indicates protocol-level errors in the framing layer, such as:
 *
 * <ul>
 *   <li><b>Oversized messages:</b> Payload exceeds the configured maximum size
 *   <li><b>Invalid length prefix:</b> The length field contains an invalid value (negative or zero
 *       when unexpected)
 *   <li><b>Corrupted frame:</b> The frame structure doesn't match the expected format
 *   <li><b>Buffer overflow:</b> Destination buffer is too small for the payload
 * </ul>
 *
 * <h2>Error Recovery</h2>
 *
 * <p>When a {@code FramingException} is caught, the connection should typically be closed because
 * the byte stream may be in an inconsistent state. If the stream position is lost, it's generally
 * not possible to resynchronize without application-level support.
 *
 * <h2>Security Considerations</h2>
 *
 * <p>The maximum message size check is critical for preventing denial-of-service attacks. A
 * malicious peer could send a frame with a very large length prefix, causing the receiver to
 * allocate excessive memory. Implementations should validate length prefixes before any allocation.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * try {
 *     int payloadLength = framingHandler.deframeMessage(source, sourceLength, destination);
 *     // Process payload...
 * } catch (FramingException e) {
 *     logger.error("Framing error, closing connection: {}", e.getMessage());
 *     connection.close();
 * }
 * }</pre>
 *
 * @see FramingHandler
 */
public class FramingException extends RuntimeException {

    /**
     * Constructs a new framing exception with the specified detail message.
     *
     * @param message the detail message describing the framing error
     */
    public FramingException(String message) {
        super(message);
    }

    /**
     * Constructs a new framing exception with the specified detail message and cause.
     *
     * @param message the detail message describing the framing error
     * @param cause the underlying cause of the framing error
     */
    public FramingException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new framing exception with the specified cause.
     *
     * <p>The detail message is set to {@code (cause == null ? null : cause.toString())}.
     *
     * @param cause the underlying cause of the framing error
     */
    public FramingException(Throwable cause) {
        super(cause);
    }
}

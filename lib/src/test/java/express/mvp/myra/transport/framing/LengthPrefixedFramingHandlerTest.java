package express.mvp.myra.transport.framing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link LengthPrefixedFramingHandler}.
 */
class LengthPrefixedFramingHandlerTest {

    private LengthPrefixedFramingHandler handler;
    private Arena arena;

    @BeforeEach
    void setUp() {
        handler = new LengthPrefixedFramingHandler();
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    // ==================== Construction Tests ====================

    @Test
    @DisplayName("Default constructor creates handler with 16MB max payload")
    void defaultConstructor() {
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler();
        assertEquals(16 * 1024 * 1024, h.getMaxPayloadSize());
        assertEquals(4, h.getHeaderSize());
    }

    @Test
    @DisplayName("Custom max payload size is respected")
    void customMaxPayloadSize() {
        int customSize = 1024 * 1024; // 1 MB
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(customSize);
        assertEquals(customSize, h.getMaxPayloadSize());
    }

    @Test
    @DisplayName("Constructor rejects non-positive max payload size")
    void rejectsNonPositiveMaxSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new LengthPrefixedFramingHandler(0));
        assertThrows(IllegalArgumentException.class,
                () -> new LengthPrefixedFramingHandler(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new LengthPrefixedFramingHandler(-100));
    }

    @Test
    @DisplayName("Constructor rejects max size that would overflow frame size")
    void rejectsOverflowingMaxSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new LengthPrefixedFramingHandler(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new LengthPrefixedFramingHandler(Integer.MAX_VALUE - 3));
    }

    @Test
    @DisplayName("Max size at boundary is accepted")
    void maxSizeAtBoundaryAccepted() {
        int maxValid = Integer.MAX_VALUE - 4;
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(maxValid);
        assertEquals(maxValid, h.getMaxPayloadSize());
    }

    // ==================== Frame/Deframe Round-Trip Tests ====================

    @Test
    @DisplayName("Frame and deframe simple message")
    void frameDeframeSimpleMessage() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(payload.length + handler.getHeaderSize());
        MemorySegment output = arena.allocate(payload.length);

        // Frame
        int frameLength = handler.frameMessage(source, payload.length, frame);
        assertEquals(payload.length + 4, frameLength);

        // Deframe
        int payloadLength = handler.deframeMessage(frame, frameLength, output);
        assertEquals(payload.length, payloadLength);

        // Verify content
        byte[] result = new byte[payloadLength];
        MemorySegment.copy(output, 0, MemorySegment.ofArray(result), 0, payloadLength);
        assertArrayEquals(payload, result);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 100, 1000, 10000, 65536})
    @DisplayName("Frame/deframe round-trip for various payload sizes")
    void frameDeframeVariousSizes(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 256);
        }

        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(size + handler.getHeaderSize());
        MemorySegment output = arena.allocate(size);

        int frameLength = handler.frameMessage(source, size, frame);
        int payloadLength = handler.deframeMessage(frame, frameLength, output);

        assertEquals(size, payloadLength);

        byte[] result = new byte[size];
        MemorySegment.copy(output, 0, MemorySegment.ofArray(result), 0, size);
        assertArrayEquals(payload, result);
    }

    @Test
    @DisplayName("Frame/deframe empty message")
    void frameDeframeEmptyMessage() {
        MemorySegment source = arena.allocate(0);
        MemorySegment frame = arena.allocate(handler.getHeaderSize());
        MemorySegment output = arena.allocate(16);

        int frameLength = handler.frameMessage(source, 0, frame);
        assertEquals(4, frameLength);

        int payloadLength = handler.deframeMessage(frame, frameLength, output);
        assertEquals(0, payloadLength);
    }

    @Test
    @DisplayName("Frame/deframe message at exactly max size")
    void frameDeframeExactlyMaxSize() {
        int maxSize = 1024; // Small for testing
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(maxSize);

        byte[] payload = new byte[maxSize];
        for (int i = 0; i < maxSize; i++) {
            payload[i] = (byte) (i % 256);
        }

        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(maxSize + h.getHeaderSize());
        MemorySegment output = arena.allocate(maxSize);

        int frameLength = h.frameMessage(source, maxSize, frame);
        assertEquals(maxSize + 4, frameLength);

        int payloadLength = h.deframeMessage(frame, frameLength, output);
        assertEquals(maxSize, payloadLength);

        byte[] result = new byte[maxSize];
        MemorySegment.copy(output, 0, MemorySegment.ofArray(result), 0, maxSize);
        assertArrayEquals(payload, result);
    }

    // ==================== Incomplete Frame Tests ====================

    @Test
    @DisplayName("Deframe returns -1 for incomplete header (0 bytes)")
    void deframeIncompleteHeaderZeroBytes() {
        MemorySegment source = arena.allocate(0);
        MemorySegment output = arena.allocate(16);

        int result = handler.deframeMessage(source, 0, output);
        assertEquals(-1, result);
    }

    @Test
    @DisplayName("Deframe returns -1 for incomplete header (1-3 bytes)")
    void deframeIncompleteHeaderPartialBytes() {
        MemorySegment source = arena.allocate(4);
        MemorySegment output = arena.allocate(16);

        assertEquals(-1, handler.deframeMessage(source, 1, output));
        assertEquals(-1, handler.deframeMessage(source, 2, output));
        assertEquals(-1, handler.deframeMessage(source, 3, output));
    }

    @Test
    @DisplayName("Deframe returns -1 for incomplete payload")
    void deframeIncompletePayload() {
        // Create a frame indicating 100 bytes of payload
        MemorySegment frame = arena.allocate(104); // 4 header + 100 payload
        frame.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0, 100);

        MemorySegment output = arena.allocate(100);

        // Provide only header + partial payload
        assertEquals(-1, handler.deframeMessage(frame, 4, output));   // 0 bytes of payload
        assertEquals(-1, handler.deframeMessage(frame, 50, output));  // 46 bytes of payload
        assertEquals(-1, handler.deframeMessage(frame, 103, output)); // 99 bytes of payload
    }

    @Test
    @DisplayName("Deframe succeeds with exact frame length")
    void deframeExactFrameLength() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(payload.length + 4);
        MemorySegment output = arena.allocate(payload.length);

        int frameLength = handler.frameMessage(source, payload.length, frame);
        int payloadLength = handler.deframeMessage(frame, frameLength, output);

        assertEquals(payload.length, payloadLength);
    }

    @Test
    @DisplayName("Deframe succeeds with extra bytes after frame")
    void deframeWithExtraBytes() {
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(payload.length + 4 + 100); // Extra space
        MemorySegment output = arena.allocate(payload.length);

        int frameLength = handler.frameMessage(source, payload.length, frame);

        // Simulate having extra bytes available
        int payloadLength = handler.deframeMessage(frame, frameLength + 50, output);

        assertEquals(payload.length, payloadLength);
    }

    // ==================== Validation Tests ====================

    @Test
    @DisplayName("Frame rejects payload exceeding max size")
    void frameRejectsOversizedPayload() {
        int maxSize = 100;
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(maxSize);

        MemorySegment source = arena.allocate(maxSize + 1);
        MemorySegment frame = arena.allocate(maxSize + 10);

        FramingException ex = assertThrows(FramingException.class,
                () -> h.frameMessage(source, maxSize + 1, frame));

        assertTrue(ex.getMessage().contains("exceeds maximum"));
    }

    @Test
    @DisplayName("Deframe rejects length prefix exceeding max size")
    void deframeRejectsOversizedLengthPrefix() {
        int maxSize = 100;
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(maxSize);

        // Create frame with length prefix indicating oversized payload
        MemorySegment frame = arena.allocate(1000);
        frame.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0, maxSize + 1);

        MemorySegment output = arena.allocate(1000);

        FramingException ex = assertThrows(FramingException.class,
                () -> h.deframeMessage(frame, 1000, output));

        assertTrue(ex.getMessage().contains("exceeds maximum"));
    }

    @Test
    @DisplayName("Deframe rejects negative length prefix")
    void deframeRejectsNegativeLengthPrefix() {
        MemorySegment frame = arena.allocate(100);
        // Write negative length (appears as large positive when read as unsigned, but we validate as signed)
        frame.set(java.lang.foreign.ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0, -1);

        MemorySegment output = arena.allocate(100);

        FramingException ex = assertThrows(FramingException.class,
                () -> handler.deframeMessage(frame, 100, output));

        assertTrue(ex.getMessage().contains("negative"));
    }

    @Test
    @DisplayName("Frame rejects negative source length")
    void frameRejectsNegativeSourceLength() {
        MemorySegment source = arena.allocate(100);
        MemorySegment frame = arena.allocate(100);

        assertThrows(IllegalArgumentException.class,
                () -> handler.frameMessage(source, -1, frame));
    }

    @Test
    @DisplayName("Deframe rejects negative source length")
    void deframeRejectsNegativeSourceLength() {
        MemorySegment source = arena.allocate(100);
        MemorySegment output = arena.allocate(100);

        assertThrows(IllegalArgumentException.class,
                () -> handler.deframeMessage(source, -1, output));
    }

    // ==================== Null Parameter Tests ====================

    @Test
    @DisplayName("Frame rejects null source")
    void frameRejectsNullSource() {
        MemorySegment frame = arena.allocate(100);

        assertThrows(NullPointerException.class,
                () -> handler.frameMessage(null, 10, frame));
    }

    @Test
    @DisplayName("Frame rejects null destination")
    void frameRejectsNullDestination() {
        MemorySegment source = arena.allocate(100);

        assertThrows(NullPointerException.class,
                () -> handler.frameMessage(source, 10, null));
    }

    @Test
    @DisplayName("Deframe rejects null source")
    void deframeRejectsNullSource() {
        MemorySegment output = arena.allocate(100);

        assertThrows(NullPointerException.class,
                () -> handler.deframeMessage(null, 10, output));
    }

    @Test
    @DisplayName("Deframe rejects null destination")
    void deframeRejectsNullDestination() {
        MemorySegment source = arena.allocate(100);

        assertThrows(NullPointerException.class,
                () -> handler.deframeMessage(source, 10, null));
    }

    // ==================== Buffer Capacity Tests ====================

    @Test
    @DisplayName("Frame throws on insufficient destination capacity")
    void frameInsufficientDestinationCapacity() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(payload.length); // Missing header space

        assertThrows(IndexOutOfBoundsException.class,
                () -> handler.frameMessage(source, payload.length, frame));
    }

    @Test
    @DisplayName("Deframe throws on insufficient destination capacity")
    void deframeInsufficientDestinationCapacity() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(payload.length + 4);
        handler.frameMessage(source, payload.length, frame);

        MemorySegment output = arena.allocate(payload.length - 1); // Too small

        assertThrows(IndexOutOfBoundsException.class,
                () -> handler.deframeMessage(frame, payload.length + 4, output));
    }

    // ==================== Big-Endian Encoding Tests ====================

    @Test
    @DisplayName("Length prefix is written in big-endian order")
    void lengthPrefixBigEndian() {
        int payloadLength = 0x01020304; // 16909060 in decimal
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(payloadLength + 1);

        MemorySegment source = arena.allocate(payloadLength);
        MemorySegment frame = arena.allocate(payloadLength + 4);

        h.frameMessage(source, payloadLength, frame);

        // Verify big-endian byte order: 01 02 03 04
        assertEquals((byte) 0x01, frame.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        assertEquals((byte) 0x02, frame.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 1));
        assertEquals((byte) 0x03, frame.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 2));
        assertEquals((byte) 0x04, frame.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 3));
    }

    @Test
    @DisplayName("Length prefix is read in big-endian order")
    void lengthPrefixReadBigEndian() {
        MemorySegment frame = arena.allocate(104);

        // Write length 100 in big-endian: 00 00 00 64
        frame.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
        frame.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
        frame.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 2, (byte) 0x00);
        frame.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 3, (byte) 0x64); // 100

        // Fill some payload
        for (int i = 0; i < 100; i++) {
            frame.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 4 + i, (byte) i);
        }

        MemorySegment output = arena.allocate(100);
        int payloadLength = handler.deframeMessage(frame, 104, output);

        assertEquals(100, payloadLength);
    }

    // ==================== Thread Safety Tests ====================

    @Test
    @DisplayName("Handler is thread-safe for concurrent operations")
    void concurrentFramingOperations() throws Exception {
        int threadCount = 10;
        int operationsPerThread = 1000;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Shared handler (should be safe to use concurrently)
        LengthPrefixedFramingHandler sharedHandler = new LengthPrefixedFramingHandler();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Arena threadArena = Arena.ofConfined()) {
                    for (int i = 0; i < operationsPerThread; i++) {
                        try {
                            // Create unique payload for this operation
                            String message = "Thread-" + threadId + "-Op-" + i;
                            byte[] payload = message.getBytes(StandardCharsets.UTF_8);

                            MemorySegment source = MemorySegment.ofArray(payload);
                            MemorySegment frame = threadArena.allocate(payload.length + 4);
                            MemorySegment output = threadArena.allocate(payload.length);

                            // Frame and deframe
                            int frameLength = sharedHandler.frameMessage(source, payload.length, frame);
                            int payloadLength = sharedHandler.deframeMessage(frame, frameLength, output);

                            // Verify
                            if (payloadLength == payload.length) {
                                byte[] result = new byte[payloadLength];
                                MemorySegment.copy(output, 0, MemorySegment.ofArray(result), 0, payloadLength);
                                if (new String(result, StandardCharsets.UTF_8).equals(message)) {
                                    successCount.incrementAndGet();
                                } else {
                                    errorCount.incrementAndGet();
                                }
                            } else {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for threads");
        executor.shutdown();

        assertEquals(0, errorCount.get(), "Should have no errors");
        assertEquals(threadCount * operationsPerThread, successCount.get(),
                "All operations should succeed");
    }

    // ==================== toString Test ====================

    @Test
    @DisplayName("toString provides useful information")
    void toStringFormat() {
        LengthPrefixedFramingHandler h = new LengthPrefixedFramingHandler(1024);
        String str = h.toString();

        assertTrue(str.contains("LengthPrefixedFramingHandler"));
        assertTrue(str.contains("headerSize=4"));
        assertTrue(str.contains("maxPayloadSize=1024"));
    }

    // ==================== Edge Case Tests ====================

    @Test
    @DisplayName("Handle single byte payload")
    void singleBytePayload() {
        byte[] payload = {0x42};
        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(5);
        MemorySegment output = arena.allocate(1);

        int frameLength = handler.frameMessage(source, 1, frame);
        assertEquals(5, frameLength);

        int payloadLength = handler.deframeMessage(frame, frameLength, output);
        assertEquals(1, payloadLength);
        assertEquals(0x42, output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
    }

    @Test
    @DisplayName("Binary data integrity preserved")
    void binaryDataIntegrity() {
        // All possible byte values
        byte[] payload = new byte[256];
        for (int i = 0; i < 256; i++) {
            payload[i] = (byte) i;
        }

        MemorySegment source = MemorySegment.ofArray(payload);
        MemorySegment frame = arena.allocate(260);
        MemorySegment output = arena.allocate(256);

        int frameLength = handler.frameMessage(source, 256, frame);
        int payloadLength = handler.deframeMessage(frame, frameLength, output);

        assertEquals(256, payloadLength);

        byte[] result = new byte[256];
        MemorySegment.copy(output, 0, MemorySegment.ofArray(result), 0, 256);
        assertArrayEquals(payload, result);
    }

    @Test
    @DisplayName("Large payload near default max")
    void largePayload() {
        // Test with 1MB payload (well under 16MB default max)
        int size = 1024 * 1024;
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 256);
        }

        try (Arena largeArena = Arena.ofConfined()) {
            MemorySegment source = MemorySegment.ofArray(payload);
            MemorySegment frame = largeArena.allocate(size + 4);
            MemorySegment output = largeArena.allocate(size);

            int frameLength = handler.frameMessage(source, size, frame);
            assertEquals(size + 4, frameLength);

            int payloadLength = handler.deframeMessage(frame, frameLength, output);
            assertEquals(size, payloadLength);

            // Spot check some values
            assertEquals((byte) 0, output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
            assertEquals((byte) 255, output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 255));
            assertEquals((byte) 0, output.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 256));
        }
    }
}

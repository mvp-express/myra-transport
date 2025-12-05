package express.mvp.myra.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Comprehensive test suite for RegisteredBufferPoolImpl. */
class RegisteredBufferPoolImplTest {

    private RegisteredBufferPoolImpl pool;
    private static final int NUM_BUFFERS = 16;
    private static final int BUFFER_SIZE = 4096;

    @BeforeEach
    void setUp() {
        pool = new RegisteredBufferPoolImpl(NUM_BUFFERS, BUFFER_SIZE);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    @Test
    void testPoolInitialization() {
        assertEquals(NUM_BUFFERS, pool.capacity());
        assertEquals(NUM_BUFFERS, pool.available());
        assertEquals(0, pool.inUse());
    }

    @Test
    void testAcquireAndRelease() {
        RegisteredBuffer buffer = pool.acquire();
        assertNotNull(buffer);
        assertEquals(NUM_BUFFERS - 1, pool.available());
        assertEquals(1, pool.inUse());

        buffer.close();
        assertEquals(NUM_BUFFERS, pool.available());
        assertEquals(0, pool.inUse());
    }

    @Test
    void testTryAcquire() {
        // Acquire all buffers
        RegisteredBuffer[] buffers = new RegisteredBuffer[NUM_BUFFERS];
        for (int i = 0; i < NUM_BUFFERS; i++) {
            buffers[i] = pool.tryAcquire();
            assertNotNull(buffers[i]);
        }

        // Pool exhausted
        assertNull(pool.tryAcquire());
        assertEquals(0, pool.available());
        assertEquals(NUM_BUFFERS, pool.inUse());

        // Release one
        buffers[0].close();
        assertNotNull(pool.tryAcquire());
    }

    @Test
    void testBufferProperties() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            assertEquals(BUFFER_SIZE, buffer.capacity());
            assertEquals(0, buffer.position());
            assertEquals(BUFFER_SIZE, buffer.limit());
            assertTrue(buffer.hasRemaining());
            assertEquals(BUFFER_SIZE, buffer.remaining());
        }
    }

    @Test
    void testBufferPositionAndLimit() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            buffer.position(100);
            assertEquals(100, buffer.position());

            buffer.limit(1000);
            assertEquals(1000, buffer.limit());
            assertEquals(900, buffer.remaining());
        }
    }

    @Test
    void testBufferClear() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            buffer.position(100);
            buffer.limit(1000);

            buffer.clear();
            assertEquals(0, buffer.position());
            assertEquals(BUFFER_SIZE, buffer.limit());
        }
    }

    @Test
    void testBufferFlip() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            buffer.position(500);
            buffer.flip();

            assertEquals(0, buffer.position());
            assertEquals(500, buffer.limit());
        }
    }

    @Test
    void testBufferIndex() {
        try (RegisteredBuffer b0 = pool.acquire();
                RegisteredBuffer b1 = pool.acquire();
                RegisteredBuffer b2 = pool.acquire()) {

            assertEquals(0, b0.index());
            assertEquals(1, b1.index());
            assertEquals(2, b2.index());
        }
    }

    @Test
    void testMemorySegmentAccess() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            MemorySegment segment = buffer.segment();
            assertNotNull(segment);
            assertEquals(BUFFER_SIZE, segment.byteSize());

            // Write and read data
            segment.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, 42);
            int value = segment.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
            assertEquals(42, value);
        }
    }

    @Test
    void testIdempotentRelease() {
        RegisteredBuffer buffer = pool.acquire();
        assertEquals(1, pool.inUse());

        buffer.close();
        assertEquals(0, pool.inUse());

        // Second close should be idempotent
        buffer.close();
        assertEquals(0, pool.inUse());
    }

    @Test
    void testConcurrentAcquireRelease() throws InterruptedException {
        int numThreads = 8;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < operationsPerThread; j++) {
                                try (RegisteredBuffer buffer = pool.acquire()) {
                                    assertNotNull(buffer);
                                    // Simulate some work
                                    MemorySegment segment = buffer.segment();
                                    segment.set(java.lang.foreign.ValueLayout.JAVA_INT, 0, j);
                                    successCount.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(numThreads * operationsPerThread, successCount.get());
        assertEquals(NUM_BUFFERS, pool.available());
        assertEquals(0, pool.inUse());
    }

    @Test
    void testPoolExhaustion() throws InterruptedException {
        // Acquire all buffers
        RegisteredBuffer[] buffers = new RegisteredBuffer[NUM_BUFFERS];
        for (int i = 0; i < NUM_BUFFERS; i++) {
            buffers[i] = pool.acquire();
        }

        // Try to acquire one more in separate thread (will block)
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(1);

        Thread thread =
                new Thread(
                        () -> {
                            blocked.countDown();
                            try (RegisteredBuffer buffer = pool.acquire()) {
                                acquired.countDown();
                            }
                        });
        thread.start();

        // Wait for thread to block
        assertTrue(blocked.await(1, TimeUnit.SECONDS));
        Thread.sleep(100); // Give time to block on acquire

        // Release one buffer
        buffers[0].close();

        // Thread should unblock
        assertTrue(acquired.await(5, TimeUnit.SECONDS));
        thread.join(1000);
    }

    @Test
    void testClosedPoolThrowsException() {
        pool.close();

        assertThrows(IllegalStateException.class, () -> pool.acquire());
        assertThrows(IllegalStateException.class, () -> pool.tryAcquire());
    }

    @Test
    void testInvalidPositionThrows() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            assertThrows(IllegalArgumentException.class, () -> buffer.position(-1));
            assertThrows(IllegalArgumentException.class, () -> buffer.position(BUFFER_SIZE + 1));
        }
    }

    @Test
    void testInvalidLimitThrows() {
        try (RegisteredBuffer buffer = pool.acquire()) {
            assertThrows(IllegalArgumentException.class, () -> buffer.limit(-1));
            assertThrows(IllegalArgumentException.class, () -> buffer.limit(BUFFER_SIZE + 1));
        }
    }

    @Test
    void testBufferResetOnRelease() {
        RegisteredBuffer buffer = pool.acquire();
        buffer.position(500);
        buffer.limit(1000);
        buffer.close();

        // Acquire again - should be reset
        buffer = pool.acquire();
        assertEquals(0, buffer.position());
        assertEquals(BUFFER_SIZE, buffer.limit());
        buffer.close();
    }

    @Test
    void testAllBuffersHaveUniqueIndices() {
        // Acquire all buffers and verify they have unique indices
        RegisteredBuffer[] buffers = new RegisteredBuffer[NUM_BUFFERS];
        boolean[] seenIndices = new boolean[NUM_BUFFERS];

        for (int i = 0; i < NUM_BUFFERS; i++) {
            buffers[i] = pool.acquire();
            int index = buffers[i].index();
            assertTrue(index >= 0 && index < NUM_BUFFERS);
            assertFalse(seenIndices[index], "Duplicate index: " + index);
            seenIndices[index] = true;
        }

        // Release all
        for (RegisteredBuffer buffer : buffers) {
            buffer.close();
        }
    }
}

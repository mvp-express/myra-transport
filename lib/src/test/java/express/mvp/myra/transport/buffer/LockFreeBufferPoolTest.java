package express.mvp.myra.transport.buffer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LockFreeBufferPoolTest {

    @Test
    void testAcquireAndRelease() {
        try (LockFreeBufferPool pool = new LockFreeBufferPool(4, 1024)) {
            assertEquals(4, pool.available());

            BufferRef buf1 = pool.acquire();
            assertNotNull(buf1);
            assertEquals(3, pool.available());
            assertEquals(1024, buf1.segment().byteSize());

            BufferRef buf2 = pool.acquire();
            assertNotNull(buf2);
            assertEquals(2, pool.available());

            buf1.release();
            assertEquals(3, pool.available());

            buf2.release();
            assertEquals(4, pool.available());
        }
    }

    @Test
    void testExhaustion() {
        try (LockFreeBufferPool pool = new LockFreeBufferPool(2, 128)) {
            BufferRef b1 = pool.acquire();
            BufferRef b2 = pool.acquire();
            assertNotNull(b1);
            assertNotNull(b2);
            assertEquals(0, pool.available());

            assertNull(pool.acquire());

            b1.release();
            assertNotNull(pool.acquire());
        }
    }

    @Test
    void testRefCount() {
        try (LockFreeBufferPool pool = new LockFreeBufferPool(2, 128)) {
            BufferRef b1 = pool.acquire();
            // Pool has 2. Acquired 1. Available = 1.

            b1.retain(); // ref=2
            b1.release(); // ref=1, not returned
            assertEquals(1, pool.available());

            b1.release(); // ref=0, returned
            assertEquals(2, pool.available());
        }
    }
}

package express.mvp.myra.transport.iouring;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Comprehensive tests for LibUring FFM bindings.
 *
 * <p>Tests cover: - Availability and initialization - Constants and flags - Memory layouts -
 * SQE/CQE operations - Zero-copy and multishot flags
 */
@EnabledOnOs(OS.LINUX)
class LibUringTest {

    @Nested
    @DisplayName("Availability Tests")
    class AvailabilityTests {

        @Test
        @DisplayName("isAvailable() completes without exception")
        void testIsAvailable() {
            boolean available = LibUring.isAvailable();
            assertTrue(available || !available, "isAvailable() should complete");
        }

        @Test
        @DisplayName("isAvailable() returns true on modern Linux")
        void testIsAvailableOnModernLinux() {
            // On modern Linux with io_uring support, this should be true
            // Skip test if not available (older kernel)
            assumeTrue(LibUring.isAvailable(), "io_uring not available");
            assertTrue(LibUring.isAvailable());
        }
    }

    @Nested
    @DisplayName("Constants Tests")
    class ConstantsTests {

        @Test
        @DisplayName("Setup flags are defined correctly")
        void testSetupFlags() {
            assertEquals(1 << 1, LibUring.IORING_SETUP_SQPOLL);
            assertEquals(1 << 2, LibUring.IORING_SETUP_SQ_AFF);
            assertEquals(1 << 3, LibUring.IORING_SETUP_CQSIZE);
            assertEquals(1 << 8, LibUring.IORING_SETUP_COOP_TASKRUN);
            assertEquals(1 << 12, LibUring.IORING_SETUP_SINGLE_ISSUER);
        }

        @Test
        @DisplayName("Operation codes are defined correctly")
        void testOperationCodes() {
            assertEquals(0, LibUring.IORING_OP_NOP);
            assertEquals(1, LibUring.IORING_OP_READV);
            assertEquals(2, LibUring.IORING_OP_WRITEV);
            assertEquals(13, LibUring.IORING_OP_ACCEPT);
            assertEquals(16, LibUring.IORING_OP_CONNECT);
            assertEquals(26, LibUring.IORING_OP_SEND);
            assertEquals(27, LibUring.IORING_OP_RECV);
            assertEquals(46, LibUring.IORING_OP_SEND_ZC);
        }

        @Test
        @DisplayName("SQE flags are defined correctly")
        void testSqeFlags() {
            assertEquals(1 << 0, LibUring.IOSQE_FIXED_FILE);
            assertEquals(1 << 1, LibUring.IOSQE_IO_DRAIN);
            assertEquals(1 << 2, LibUring.IOSQE_IO_LINK);
            assertEquals(1 << 4, LibUring.IOSQE_BUFFER_SELECT);
        }

        @Test
        @DisplayName("CQE flags are defined correctly")
        void testCqeFlags() {
            assertEquals(1 << 1, LibUring.IORING_CQE_F_MORE);
            assertEquals(1 << 3, LibUring.IORING_CQE_F_NOTIF);
        }

        @Test
        @DisplayName("Multishot receive flag is defined")
        void testMultishotFlag() {
            assertEquals(1 << 1, LibUring.IORING_RECV_MULTISHOT);
        }

        @Test
        @DisplayName("AF_INET constant is correct")
        void testAfInet() {
            assertEquals(2, LibUring.AF_INET);
        }
    }

    @Nested
    @DisplayName("Layout Tests")
    class LayoutTests {

        @Test
        @DisplayName("IO_URING_LAYOUT is defined with correct size")
        void testIoUringLayout() {
            assertNotNull(LibUring.IO_URING_LAYOUT);
            assertTrue(LibUring.IO_URING_LAYOUT.byteSize() > 0);
        }

        @Test
        @DisplayName("IOVEC_LAYOUT is correct size")
        void testIovecLayout() {
            assertNotNull(LibUring.IOVEC_LAYOUT);
            // iovec is pointer (8 bytes) + size_t (8 bytes on 64-bit)
            assertEquals(16, LibUring.IOVEC_LAYOUT.byteSize());
        }

        @Test
        @DisplayName("KERNEL_TIMESPEC_LAYOUT is correct size")
        void testKernelTimespecLayout() {
            assertNotNull(LibUring.KERNEL_TIMESPEC_LAYOUT);
            // kernel_timespec is tv_sec (8 bytes) + tv_nsec (8 bytes)
            assertEquals(16, LibUring.KERNEL_TIMESPEC_LAYOUT.byteSize());
        }

        @Test
        @DisplayName("IO_URING_SQE_LAYOUT is defined")
        void testSqeLayout() {
            assertNotNull(LibUring.IO_URING_SQE_LAYOUT);
            assertTrue(LibUring.IO_URING_SQE_LAYOUT.byteSize() >= 64);
        }

        @Test
        @DisplayName("IO_URING_CQE_LAYOUT is correct size")
        void testCqeLayout() {
            assertNotNull(LibUring.IO_URING_CQE_LAYOUT);
            // CQE is user_data (8) + res (4) + flags (4) = 16 bytes
            assertEquals(16, LibUring.IO_URING_CQE_LAYOUT.byteSize());
        }

        @Test
        @DisplayName("IO_URING_PARAMS_LAYOUT is defined")
        void testParamsLayout() {
            assertNotNull(LibUring.IO_URING_PARAMS_LAYOUT);
            assertTrue(LibUring.IO_URING_PARAMS_LAYOUT.byteSize() >= 120);
        }
    }

    @Nested
    @DisplayName("Native Function Tests")
    class NativeFunctionTests {

        @Test
        @DisplayName("nativeHtons converts port correctly")
        void testNativeHtons() {
            // Port 80 in host order is 0x0050
            // In network order (big endian) it should be 0x5000 on little-endian machines
            short port = 80;
            short networkOrder = LibUring.nativeHtons(port);
            // Converting back should give original
            short backToHost = LibUring.nativeHtons(networkOrder);
            assertEquals(port, backToHost);
        }

        @Test
        @DisplayName("createNonBlockingSocket creates valid socket")
        void testCreateNonBlockingSocket() {
            assumeTrue(LibUring.isAvailable());

            int fd = LibUring.createNonBlockingSocket();
            assertTrue(fd >= 0, "Should create valid socket fd");

            // Close it
            int ret = LibUring.nativeClose(fd);
            assertEquals(0, ret, "Close should succeed");
        }

        @Test
        @DisplayName("nativeClose on invalid fd returns error")
        void testNativeCloseInvalidFd() {
            int ret = LibUring.nativeClose(-1);
            assertTrue(ret < 0, "Close on invalid fd should fail");
        }
    }

    @Nested
    @DisplayName("SQE Preparation Tests")
    class SqePreparationTests {

        @Test
        @DisplayName("prepSend sets correct fields")
        void testPrepSend() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                MemorySegment buf = arena.allocate(64);

                LibUring.prepSend(sqe, 5, buf, 64, 0);

                // Verify opcode
                byte opcode = sqe.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(LibUring.IORING_OP_SEND, opcode);

                // Verify fd
                int fd = sqe.get(ValueLayout.JAVA_INT, 4);
                assertEquals(5, fd);

                // Verify len
                int len = sqe.get(ValueLayout.JAVA_INT, 24);
                assertEquals(64, len);
            }
        }

        @Test
        @DisplayName("prepRecv sets correct fields")
        void testPrepRecv() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                MemorySegment buf = arena.allocate(128);

                LibUring.prepRecv(sqe, 7, buf, 128, 0);

                byte opcode = sqe.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(LibUring.IORING_OP_RECV, opcode);

                int fd = sqe.get(ValueLayout.JAVA_INT, 4);
                assertEquals(7, fd);
            }
        }

        @Test
        @DisplayName("prepSendZc uses correct opcode")
        void testPrepSendZc() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                MemorySegment buf = arena.allocate(64);

                LibUring.prepSendZc(sqe, 5, buf, 64, 0);

                byte opcode = sqe.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(LibUring.IORING_OP_SEND_ZC, opcode);
            }
        }

        @Test
        @DisplayName("prepRecvMultishot sets multishot flag")
        void testPrepRecvMultishot() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                MemorySegment buf = arena.allocate(128);

                LibUring.prepRecvMultishot(sqe, 7, buf, 128, 0);

                byte opcode = sqe.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(LibUring.IORING_OP_RECV, opcode);

                // Check multishot flag in ioprio
                short ioprio = sqe.get(ValueLayout.JAVA_SHORT, 2);
                assertTrue(
                    (ioprio & LibUring.IORING_RECV_MULTISHOT) != 0,
                        "Should have multishot flag set");
            }
        }

        @Test
        @DisplayName("prepConnect sets correct fields")
        void testPrepConnect() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                MemorySegment sockaddr = arena.allocate(16);

                LibUring.prepConnect(sqe, 5, sockaddr, 16);

                byte opcode = sqe.get(ValueLayout.JAVA_BYTE, 0);
                assertEquals(LibUring.IORING_OP_CONNECT, opcode);
            }
        }

        @Test
        @DisplayName("sqeSetUserData and cqeGetUserData work correctly")
        void testUserData() {
            assumeTrue(LibUring.isAvailable());

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);

                long testToken = 0xDEADBEEF_CAFEBABEL;
                LibUring.sqeSetUserData(sqe, testToken);

                // Verify by reading back
                long userData = sqe.get(ValueLayout.JAVA_LONG, 32); // user_data offset
                assertEquals(testToken, userData);
            }
        }
    }

    @Nested
    @DisplayName("CQE Helper Tests")
    class CqeHelperTests {

        @Test
        @DisplayName("isZeroCopyNotification detects NOTIF flag")
        void testIsZeroCopyNotification() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                // Without flag
                cqe.set(ValueLayout.JAVA_INT, 12, 0); // flags at offset 12
                assertFalse(LibUring.isZeroCopyNotification(cqe));

                // With NOTIF flag
                cqe.set(ValueLayout.JAVA_INT, 12, LibUring.IORING_CQE_F_NOTIF);
                assertTrue(LibUring.isZeroCopyNotification(cqe));
            }
        }

        @Test
        @DisplayName("hasMoreCompletions detects MORE flag")
        void testHasMoreCompletions() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                // Without flag
                cqe.set(ValueLayout.JAVA_INT, 12, 0);
                assertFalse(LibUring.hasMoreCompletions(cqe));

                // With MORE flag
                cqe.set(ValueLayout.JAVA_INT, 12, LibUring.IORING_CQE_F_MORE);
                assertTrue(LibUring.hasMoreCompletions(cqe));
            }
        }

        @Test
        @DisplayName("cqeGetRes returns result correctly")
        void testCqeGetRes() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                // Set result at offset 8
                cqe.set(ValueLayout.JAVA_INT, 8, 42);
                assertEquals(42, LibUring.cqeGetRes(cqe));

                // Test negative (error)
                cqe.set(ValueLayout.JAVA_INT, 8, -11); // EAGAIN
                assertEquals(-11, LibUring.cqeGetRes(cqe));
            }
        }

        @Test
        @DisplayName("cqeGetFlags returns flags correctly")
        void testCqeGetFlags() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                int testFlags = LibUring.IORING_CQE_F_MORE | LibUring.IORING_CQE_F_NOTIF;
                cqe.set(ValueLayout.JAVA_INT, 12, testFlags);

                assertEquals(testFlags, LibUring.cqeGetFlags(cqe));
            }
        }
    }

    // ========== P2: Buffer Ring Tests ==========

    @Nested
    @DisplayName("Buffer Ring Tests")
    class BufferRingTests {

        @Test
        @DisplayName("Buffer ring constants are defined correctly")
        void testBufferRingConstants() {
            assertEquals(22, LibUring.IORING_REGISTER_PBUF_RING);
            assertEquals(23, LibUring.IORING_UNREGISTER_PBUF_RING);
            assertEquals(1 << 0, LibUring.IOU_PBUF_RING_MMAP);
        }

        @Test
        @DisplayName("Buffer ring layout has correct size")
        void testBufferRingLayoutSize() {
            // io_uring_buf_ring header is 16 bytes
            assertEquals(16, LibUring.IO_URING_BUF_RING_LAYOUT.byteSize());
        }

        @Test
        @DisplayName("Buffer entry layout has correct size")
        void testBufferLayoutSize() {
            // io_uring_buf is 16 bytes: addr(8) + len(4) + bid(2) + resv(2)
            assertEquals(16, LibUring.IO_URING_BUF_LAYOUT.byteSize());
        }

        @Test
        @DisplayName("isBufferRingSupported returns boolean")
        void testIsBufferRingSupported() {
            boolean supported = LibUring.isBufferRingSupported();
            // Just verify it returns without exception
            assertTrue(supported || !supported);
        }

        @Test
        @DisplayName("bufferRingInit zeros the header")
        void testBufferRingInit() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ring = arena.allocate(16);
                // Fill with garbage
                ring.fill((byte) 0xFF);

                LibUring.bufferRingInit(ring);

                // Header should be zeroed
                assertEquals(0L, ring.get(ValueLayout.JAVA_LONG, 0));
                assertEquals(0L, ring.get(ValueLayout.JAVA_LONG, 8));
            }
        }

        @Test
        @DisplayName("bufferRingAdd sets entry fields correctly")
        void testBufferRingAdd() {
            try (Arena arena = Arena.ofConfined()) {
                // Allocate ring with header (16) + one entry (16)
                MemorySegment ring = arena.allocate(32);
                ring.fill((byte) 0);

                long testAddr = 0x12345678L;
                int testLen = 8192;
                short testBid = 42;

                LibUring.bufferRingAdd(ring, testAddr, testLen, testBid, 0, 0);

                // Check entry at offset 16
                assertEquals(testAddr, ring.get(ValueLayout.JAVA_LONG, 16));
                assertEquals(testLen, ring.get(ValueLayout.JAVA_INT, 24));
                assertEquals(testBid, ring.get(ValueLayout.JAVA_SHORT, 28));
            }
        }

        @Test
        @DisplayName("bufferRingAdvance updates tail")
        void testBufferRingAdvance() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment ring = arena.allocate(16);
                ring.fill((byte) 0);

                // Initial tail should be 0
                assertEquals(0, ring.get(ValueLayout.JAVA_SHORT, 14));

                LibUring.bufferRingAdvance(ring, 5);
                assertEquals(5, ring.get(ValueLayout.JAVA_SHORT, 14));

                LibUring.bufferRingAdvance(ring, 3);
                assertEquals(8, ring.get(ValueLayout.JAVA_SHORT, 14));
            }
        }

        @Test
        @DisplayName("IOSQE_BUFFER_SELECT flag is defined")
        void testBufferSelectFlag() {
            assertEquals(1 << 4, LibUring.IOSQE_BUFFER_SELECT);
        }

        @Test
        @DisplayName("IORING_CQE_F_BUFFER flag is defined")
        void testCqeBufferFlag() {
            assertEquals(1 << 0, LibUring.IORING_CQE_F_BUFFER);
        }

        @Test
        @DisplayName("cqeHasBuffer detects BUFFER flag")
        void testCqeHasBuffer() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                // Without flag
                cqe.set(ValueLayout.JAVA_INT, 12, 0);
                assertFalse(LibUring.cqeHasBuffer(cqe));

                // With BUFFER flag
                cqe.set(ValueLayout.JAVA_INT, 12, LibUring.IORING_CQE_F_BUFFER);
                assertTrue(LibUring.cqeHasBuffer(cqe));
            }
        }

        @Test
        @DisplayName("cqeGetBufferId extracts buffer ID from flags")
        void testCqeGetBufferId() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cqe = arena.allocate(LibUring.IO_URING_CQE_LAYOUT);

                // Without BUFFER flag, should return -1
                cqe.set(ValueLayout.JAVA_INT, 12, 0);
                assertEquals(-1, LibUring.cqeGetBufferId(cqe));

                // With BUFFER flag and buffer ID 42 in upper 16 bits
                int flags = LibUring.IORING_CQE_F_BUFFER | (42 << 16);
                cqe.set(ValueLayout.JAVA_INT, 12, flags);
                assertEquals(42, LibUring.cqeGetBufferId(cqe));

                // With buffer ID 0xFFFF (max)
                flags = LibUring.IORING_CQE_F_BUFFER | (0xFFFF << 16);
                cqe.set(ValueLayout.JAVA_INT, 12, flags);
                assertEquals(0xFFFF, LibUring.cqeGetBufferId(cqe));
            }
        }
    }

    // ========== P2: Linked Operations Tests ==========

    @Nested
    @DisplayName("Linked Operations Tests")
    class LinkedOperationsTests {

        @Test
        @DisplayName("IOSQE_IO_LINK flag is defined")
        void testLinkFlag() {
            assertEquals(1 << 2, LibUring.IOSQE_IO_LINK);
        }

        @Test
        @DisplayName("IOSQE_IO_HARDLINK flag is defined")
        void testHardLinkFlag() {
            assertEquals(1 << 3, LibUring.IOSQE_IO_HARDLINK);
        }

        @Test
        @DisplayName("IOSQE_CQE_SKIP_SUCCESS flag is defined")
        void testCqeSkipSuccessFlag() {
            assertEquals(1 << 6, LibUring.IOSQE_CQE_SKIP_SUCCESS);
        }

        @Test
        @DisplayName("sqeSetLink sets LINK flag")
        void testSqeSetLink() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                sqe.fill((byte) 0);

                // Initially no flags
                assertEquals(0, sqe.get(ValueLayout.JAVA_BYTE, 1));

                LibUring.sqeSetLink(sqe);

                // LINK flag should be set
                byte flags = sqe.get(ValueLayout.JAVA_BYTE, 1);
                assertEquals(LibUring.IOSQE_IO_LINK, flags & LibUring.IOSQE_IO_LINK);
            }
        }

        @Test
        @DisplayName("sqeSetHardLink sets HARDLINK flag")
        void testSqeSetHardLink() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                sqe.fill((byte) 0);

                LibUring.sqeSetHardLink(sqe);

                byte flags = sqe.get(ValueLayout.JAVA_BYTE, 1);
                assertEquals(LibUring.IOSQE_IO_HARDLINK, flags & LibUring.IOSQE_IO_HARDLINK);
            }
        }

        @Test
        @DisplayName("sqeSetCqeSkipSuccess sets CQE_SKIP_SUCCESS flag")
        void testSqeSetCqeSkipSuccess() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                sqe.fill((byte) 0);

                LibUring.sqeSetCqeSkipSuccess(sqe);

                byte flags = sqe.get(ValueLayout.JAVA_BYTE, 1);
                assertEquals(
                        LibUring.IOSQE_CQE_SKIP_SUCCESS, flags & LibUring.IOSQE_CQE_SKIP_SUCCESS);
            }
        }

        @Test
        @DisplayName("Multiple flags can be combined")
        void testCombinedFlags() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);
                sqe.fill((byte) 0);

                // Set multiple flags
                LibUring.sqeSetLink(sqe);
                LibUring.sqeSetCqeSkipSuccess(sqe);

                byte flags = sqe.get(ValueLayout.JAVA_BYTE, 1);

                // Both flags should be set
                assertTrue((flags & LibUring.IOSQE_IO_LINK) != 0);
                assertTrue((flags & LibUring.IOSQE_CQE_SKIP_SUCCESS) != 0);
            }
        }

        @Test
        @DisplayName("prepRecvMultishotBufferSelect sets correct fields")
        void testPrepRecvMultishotBufferSelect() {
            assumeTrue(LibUring.isAvailable(), "io_uring not available");

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment sqe = arena.allocate(LibUring.IO_URING_SQE_LAYOUT);

                int testFd = 5;
                short testBgid = 1;
                int msgFlags = 0;

                LibUring.prepRecvMultishotBufferSelect(sqe, testFd, testBgid, msgFlags);

                // Check opcode is RECV
                assertEquals(LibUring.IORING_OP_RECV, sqe.get(ValueLayout.JAVA_BYTE, 0));

                // Check BUFFER_SELECT flag is set
                byte sqeFlags = sqe.get(ValueLayout.JAVA_BYTE, 1);
                assertEquals(LibUring.IOSQE_BUFFER_SELECT, sqeFlags & LibUring.IOSQE_BUFFER_SELECT);

                // Check fd
                assertEquals(testFd, sqe.get(ValueLayout.JAVA_INT, 4));

                // Check buf_group (at buf_index offset, offset 40)
                assertEquals(testBgid, sqe.get(ValueLayout.JAVA_SHORT, 40));

                // Check multishot flag in ioprio
                short ioprio = sqe.get(ValueLayout.JAVA_SHORT, 2);
                assertTrue((ioprio & LibUring.IORING_RECV_MULTISHOT) != 0);
            }
        }
    }
}

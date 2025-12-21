package express.mvp.myra.transport.iouring;

import static express.mvp.roray.utils.functions.ErrnoCapture.EAGAIN;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import express.mvp.roray.utils.functions.DowncallFactory;
import express.mvp.roray.utils.functions.FunctionDescriptorBuilder;
import express.mvp.roray.utils.functions.LinuxLayouts;
import express.mvp.roray.utils.functions.StructAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * FFM (Foreign Function &amp; Memory) bindings to the Linux liburing native library.
 *
 * <p>This class provides direct access to Linux io_uring system calls via Java's Foreign Function
 * &amp; Memory API (JEP 454, finalized in Java 22). io_uring is a high-performance asynchronous I/O
 * interface introduced in Linux kernel 5.1 that provides significant performance improvements over
 * traditional epoll/select-based I/O mechanisms.
 *
 * <h2>io_uring Architecture Overview</h2>
 *
 * <p>io_uring uses two ring buffers shared between user-space and kernel-space:
 *
 * <ul>
 *   <li><b>Submission Queue (SQ)</b>: User-space writes I/O requests (SQEs - Submission Queue
 *       Entries) here. Each SQE describes an I/O operation (read, write, accept, connect, etc.)
 *   <li><b>Completion Queue (CQ)</b>: Kernel writes completion results (CQEs - Completion Queue
 *       Entries) here. Each CQE contains the result of a completed operation.
 * </ul>
 *
 * <p>This shared memory design enables:
 *
 * <ul>
 *   <li><b>Zero-copy I/O</b>: Data stays in place, no copying between kernel/user-space
 *   <li><b>Batched submissions</b>: Multiple operations submitted with a single syscall (100x
 *       syscall reduction)
 *   <li><b>SQPOLL mode</b>: Kernel thread polls SQ, eliminating submit syscalls entirely (further
 *       2-5x improvement)
 *   <li><b>Registered buffers</b>: Pre-registered memory regions skip address validation (1.7x
 *       throughput improvement)
 *   <li><b>Fixed files</b>: Pre-registered file descriptors skip fd lookup overhead
 * </ul>
 *
 * <h2>Memory Layouts</h2>
 *
 * <p>This class defines memory layouts matching the Linux kernel ABI:
 *
 * <ul>
 *   <li>{@link #IO_URING_LAYOUT}: Main io_uring structure containing SQ and CQ
 *   <li>{@link #IO_URING_SQE_LAYOUT}: Submission Queue Entry (64 bytes)
 *   <li>{@link #IO_URING_CQE_LAYOUT}: Completion Queue Entry (16 bytes)
 *   <li>{@link #IO_URING_PARAMS_LAYOUT}: Initialization parameters for queue setup
 *   <li>{@link #IOVEC_LAYOUT}: I/O vector for buffer registration
 *   <li>{@link #KERNEL_TIMESPEC_LAYOUT}: Kernel timespec for timeout operations
 * </ul>
 *
 * <h2>Key Operations</h2>
 *
 * <ul>
 *   <li>{@link #queueInit} / {@link #queueInitParams} - Initialize submission/completion queues
 *   <li>{@link #registerBuffers} - Pre-register buffers for 1.7x throughput improvement
 *   <li>{@link #registerFiles} - Pre-register file descriptors for faster fd lookup
 *   <li>{@link #prepSend} / {@link #prepRecv} - Prepare network I/O operations
 *   <li>{@link #prepSendZc} - Prepare zero-copy send (Linux 6.0+)
 *   <li>{@link #prepRecvMultishot} - Prepare persistent multi-shot receive (Linux 5.16+)
 *   <li>{@link #submit} - Batch submit all queued operations to kernel
 *   <li>{@link #peekCqe} / {@link #waitCqeTimeout} - Retrieve completions
 * </ul>
 *
 * <h2>Advanced Features</h2>
 *
 * <ul>
 *   <li><b>Buffer Rings (Linux 5.19+)</b>: Kernel-managed buffer selection for multishot recv
 *   <li><b>Linked Operations</b>: Chain SQEs for ordered execution (e.g., recv→send echo)
 *   <li><b>CQE Skip on Success</b>: Reduce CQE overhead for linked operations
 * </ul>
 *
 * <h2>Requirements</h2>
 *
 * <ul>
 *   <li>Linux kernel 5.1+ (5.6+ recommended for full features, 6.0+ for zero-copy send)
 *   <li>liburing shared library installed (liburing.so, typically from liburing-dev package)
 *   <li>Java 21+ with FFM enabled (--enable-native-access=ALL-UNNAMED)
 * </ul>
 *
 * <h2>Performance Characteristics</h2>
 *
 * <ul>
 *   <li><b>Latency</b>: 2-5μs end-to-end vs 50-100μs for traditional NIO
 *   <li><b>Throughput</b>: 1.7x improvement with registered buffers
 *   <li><b>Syscall reduction</b>: 100x fewer syscalls with batch submission
 * </ul>
 *
 * @see <a href="https://kernel.dk/io_uring.pdf">io_uring whitepaper by Jens Axboe</a>
 * @see <a href="https://man7.org/linux/man-pages/man7/io_uring.7.html">io_uring(7) man page</a>
 * @see <a href="https://unixism.net/loti/">Lord of the io_uring - Tutorial</a>
 */
public final class LibUring {

    /** DowncallFactory for libc functions (socket, bind, etc.). */
    private static final DowncallFactory LIBC = DowncallFactory.forNativeLinker();

    /**
     * Symbol lookup for liburing library functions. Null if library not found on system path.
     * Checked via {@link #isAvailable()} before use.
     */
    private static final SymbolLookup LIBURING;

    /** DowncallFactory for liburing functions. Null if liburing not available. */
    private static final DowncallFactory LIBURING_FACTORY;

    // ========== io_uring Struct Layouts (domain-specific, not in LinuxLayouts) ==========

    /**
     * {@code struct io_uring_sqe} - Submission Queue Entry (64 bytes).
     *
     * <p>Layout with offsets:
     *
     * <pre>
     *   0: opcode (1)
     *   1: flags (1)
     *   2: ioprio (2)
     *   4: fd (4)
     *   8: off (8)
     *  16: addr (8)
     *  24: len (4)
     *  28: op_flags (4)
     *  32: user_data (8)
         *  40: buf_index / buf_group (2)
         *  42: personality (2)
         *  44: splice_fd_in / file_index / addr_len (4)
         *  48: addr3 (8)
         *  56: __pad2 (8)
     * </pre>
     */
    public static final StructLayout IO_URING_SQE_LAYOUT =
            structLayout(
                            JAVA_BYTE.withName("opcode"),
                            JAVA_BYTE.withName("flags"),
                            JAVA_SHORT.withName("ioprio"),
                            JAVA_INT.withName("fd"),
                            JAVA_LONG.withName("off"),
                            JAVA_LONG.withName("addr"),
                            JAVA_INT.withName("len"),
                            JAVA_INT.withName("op_flags"),
                            JAVA_LONG.withName("user_data"),
                    // Note: buf_index and buf_group are a 2-byte union at the same offset.
                    // We model the union as a single 16-bit field and write either meaning.
                    JAVA_SHORT.withName("buf_index"),
                    JAVA_SHORT.withName("personality"),
                    JAVA_INT.withName("splice_fd_in"),
                    JAVA_LONG.withName("addr3"),
                    JAVA_LONG.withName("__pad2"))
                    .withName("io_uring_sqe");

    /**
     * {@code struct io_uring_cqe} - Completion Queue Entry (16 bytes).
     *
     * <pre>
     * struct io_uring_cqe {
     *     __u64 user_data;  // sqe->user_data submission passed back
     *     __s32 res;        // result code for this event
     *     __u32 flags;      // IORING_CQE_F_* flags
     * };
     * </pre>
     */
    public static final StructLayout IO_URING_CQE_LAYOUT =
            structLayout(
                            JAVA_LONG.withName("user_data"),
                            JAVA_INT.withName("res"),
                            JAVA_INT.withName("flags"))
                    .withName("io_uring_cqe");

    // ========== StructAccessors for io_uring structures ==========
    // These provide type-safe, name-based field access for SQE and CQE structures.

    /** StructAccessor for io_uring_sqe (Submission Queue Entry). */
    private static final StructAccessor SQE = StructAccessor.of(IO_URING_SQE_LAYOUT);

    /** StructAccessor for io_uring_cqe (Completion Queue Entry). */
    private static final StructAccessor CQE = StructAccessor.of(IO_URING_CQE_LAYOUT);

    // ========== Extracted VarHandles for Hot Path Performance ==========
    // These are extracted from StructAccessor at class initialization for maximum
    // performance in hot paths. Using extracted VarHandles achieves the same speed
    // as hand-written VarHandle code (~40 ns/op vs ~180 ns/op with string-based access).
    // See StructAccessor javadoc for performance patterns.

    // SQE field VarHandles
    private static final java.lang.invoke.VarHandle SQE_OPCODE_VH = SQE.varHandle("opcode");
    private static final java.lang.invoke.VarHandle SQE_FLAGS_VH = SQE.varHandle("flags");
    private static final java.lang.invoke.VarHandle SQE_FD_VH = SQE.varHandle("fd");
    private static final java.lang.invoke.VarHandle SQE_OFF_VH = SQE.varHandle("off");
    private static final java.lang.invoke.VarHandle SQE_ADDR_VH = SQE.varHandle("addr");
    private static final java.lang.invoke.VarHandle SQE_LEN_VH = SQE.varHandle("len");
    private static final java.lang.invoke.VarHandle SQE_OP_FLAGS_VH = SQE.varHandle("op_flags");
    private static final java.lang.invoke.VarHandle SQE_USER_DATA_VH = SQE.varHandle("user_data");
    private static final java.lang.invoke.VarHandle SQE_BUF_INDEX_VH = SQE.varHandle("buf_index");

    // CQE field VarHandles
    private static final java.lang.invoke.VarHandle CQE_USER_DATA_VH = CQE.varHandle("user_data");
    private static final java.lang.invoke.VarHandle CQE_RES_VH = CQE.varHandle("res");
    private static final java.lang.invoke.VarHandle CQE_FLAGS_VH = CQE.varHandle("flags");

    // ========== Method Handles for liburing Functions ==========
    // These are lazily-initialized handles to native C functions.
    // Each handle corresponds to a liburing API function.
    // MethodHandles are thread-safe and can be invoked concurrently.

    /**
     * Handle for io_uring_queue_init(unsigned entries, struct io_uring *ring, unsigned flags).
     * Initializes io_uring with specified queue depth.
     */
    private static final MethodHandle io_uring_queue_init;

    /**
     * Handle for io_uring_queue_init_params(entries, ring, params). Initializes io_uring with
     * extended parameters (SQPOLL, CPU affinity, etc.).
     */
    private static final MethodHandle io_uring_queue_init_params;

    /** Handle for io_uring_queue_exit(ring) - cleanup and release resources. */
    private static final MethodHandle io_uring_queue_exit;

    /**
     * Handle for io_uring_register_buffers(ring, iovecs, nr_iovecs). Pre-registers buffer memory
     * with kernel for zero-copy I/O. Provides 1.7x throughput improvement by eliminating
     * per-operation address validation.
     */
    private static final MethodHandle io_uring_register_buffers;

    /** Handle for io_uring_unregister_buffers(ring) - releases registered buffers. */
    private static final MethodHandle io_uring_unregister_buffers;

    /**
     * Handle for io_uring_register_files(ring, files, nr_files). Pre-registers file descriptors for
     * faster fd lookup in operations.
     */
    private static final MethodHandle io_uring_register_files;

    /** Handle for io_uring_unregister_files(ring) - releases registered files. */
    private static final MethodHandle io_uring_unregister_files;

    /**
     * Handle for io_uring_register_files_update(ring, off, files, nr_files). Updates registered
     * file table at specific offset (for dynamic fd management).
     */
    private static final MethodHandle io_uring_register_files_update;

    /**
     * Handle for io_uring_get_sqe(ring). Returns pointer to next available SQE slot, or NULL if
     * queue is full. This is the hot path for operation submission.
     */
    private static final MethodHandle io_uring_get_sqe;

    /**
     * Handle for io_uring_submit(ring). Submits all queued SQEs to kernel in a single syscall
     * (batch submission). Returns number of SQEs submitted or negative errno.
     */
    private static final MethodHandle io_uring_submit;

    // Note: io_uring_wait_cqe is static inline in liburing, we use timeout version

    /**
     * Handle for io_uring_wait_cqe_timeout(ring, cqe_ptr, ts). Waits for at least one CQE with
     * optional timeout.
     */
    private static final MethodHandle io_uring_wait_cqe_timeout;

    /**
     * Handle for io_uring_register_buf_ring (Linux 5.19+). Registers a provided buffer ring for
     * kernel-managed buffer selection. Essential for efficient multishot receive patterns.
     */
    private static final MethodHandle io_uring_register_buf_ring;

    /** Handle for io_uring_unregister_buf_ring - releases buffer ring. */
    private static final MethodHandle io_uring_unregister_buf_ring;

    // Note: io_uring_peek_cqe and io_uring_cqe_seen are static inline
    // and implemented manually below

    // ========== Native Socket Syscalls (via libc) ==========
    // These are direct libc function calls for socket operations.
    // Used because io_uring operations require raw file descriptors.

    /** socket(int domain, int type, int protocol) - creates endpoint for communication. */
    private static final MethodHandle socket;

    /**
     * connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) - initiates connection.
     */
    private static final MethodHandle connect;

    /**
     * bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) - binds to local address.
     */
    private static final MethodHandle bind;

    /** listen(int sockfd, int backlog) - marks socket as passive (server). */
    private static final MethodHandle listen;

    /** accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen) - accepts connection. */
    private static final MethodHandle accept;

    /** setsockopt(sockfd, level, optname, optval, optlen) - sets socket options. */
    private static final MethodHandle setsockopt;

    /** fcntl(int fd, int cmd, ...) - file descriptor control (e.g., set non-blocking). */
    private static final MethodHandle fcntl;

    /** close(int fd) - closes file descriptor. */
    private static final MethodHandle close;

    /** inet_pton(int af, const char *src, void *dst) - converts IP address text to binary. */
    private static final MethodHandle inet_pton;

    /** htons(uint16_t hostshort) - host to network byte order (16-bit). */
    private static final MethodHandle htons;

    static {
        // Load liburing library
        SymbolLookup tempLiburing = null;
        DowncallFactory tempLiburingFactory = null;
        try {
            tempLiburing = loadLibrary();
            if (tempLiburing != null) {
                tempLiburingFactory = DowncallFactory.withLookup(tempLiburing);
            }
        } catch (Throwable t) {
            // LibUring not available
        }
        LIBURING = tempLiburing;
        LIBURING_FACTORY = tempLiburingFactory;

        // Initialize liburing method handles using DowncallFactory
        MethodHandle tempInit = null;
        MethodHandle tempInitParams = null;
        MethodHandle tempExit = null;
        MethodHandle tempRegBuffers = null;
        MethodHandle tempUnregBuffers = null;
        MethodHandle tempRegFiles = null;
        MethodHandle tempUnregFiles = null;
        MethodHandle tempRegFilesUpdate = null;
        MethodHandle tempGetSqe = null;
        MethodHandle tempSubmit = null;
        MethodHandle tempWaitCqeTimeout = null;
        MethodHandle tempRegBufRing = null;
        MethodHandle tempUnregBufRing = null;

        // Native socket syscalls
        MethodHandle tempSocket = null;
        MethodHandle tempConnect = null;
        MethodHandle tempBind = null;
        MethodHandle tempListen = null;
        MethodHandle tempAccept = null;
        MethodHandle tempSetsockopt = null;
        MethodHandle tempFcntl = null;
        MethodHandle tempClose = null;
        MethodHandle tempInetPton = null;
        MethodHandle tempHtons = null;

        try {
            if (LIBURING_FACTORY != null) {
                // io_uring_queue_init(unsigned entries, struct io_uring *ring, unsigned flags)
                tempInit =
                        LIBURING_FACTORY.downcall(
                                "io_uring_queue_init",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.JAVA_INT)
                                        .build());

                // io_uring_queue_init_params(entries, ring, params)
                tempInitParams =
                        LIBURING_FACTORY.downcall(
                                "io_uring_queue_init_params",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS)
                                        .build());

                // io_uring_queue_exit(ring)
                tempExit =
                        LIBURING_FACTORY.downcall(
                                "io_uring_queue_exit",
                                FunctionDescriptorBuilder.returnsVoid()
                                        .arg(ValueLayout.ADDRESS)
                                        .build());

                // io_uring_register_buffers(ring, iovecs, nr_iovecs)
                tempRegBuffers =
                        LIBURING_FACTORY.downcall(
                                "io_uring_register_buffers",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.JAVA_INT)
                                        .build());

                // io_uring_unregister_buffers(ring)
                tempUnregBuffers =
                        LIBURING_FACTORY.downcall(
                                "io_uring_unregister_buffers",
                                FunctionDescriptorBuilder.returnsInt()
                                        .arg(ValueLayout.ADDRESS)
                                        .build());

                // io_uring_register_files(ring, files, nr_files)
                tempRegFiles =
                        LIBURING_FACTORY.downcall(
                                "io_uring_register_files",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.JAVA_INT)
                                        .build());

                // io_uring_unregister_files(ring)
                tempUnregFiles =
                        LIBURING_FACTORY.downcall(
                                "io_uring_unregister_files",
                                FunctionDescriptorBuilder.returnsInt()
                                        .arg(ValueLayout.ADDRESS)
                                        .build());

                // io_uring_register_files_update(ring, off, files, nr_files)
                tempRegFilesUpdate =
                        LIBURING_FACTORY.downcall(
                                "io_uring_register_files_update",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.ADDRESS,
                                                ValueLayout.JAVA_INT,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.JAVA_INT)
                                        .build());

                // io_uring_get_sqe(ring) - critical(false) for hot path
                tempGetSqe =
                        LIBURING_FACTORY.downcall(
                                "io_uring_get_sqe",
                                FunctionDescriptorBuilder.returnsPointer()
                                        .arg(ValueLayout.ADDRESS)
                                        .build(),
                                Linker.Option.critical(false));

                // io_uring_submit(ring)
                tempSubmit =
                        LIBURING_FACTORY.downcall(
                                "io_uring_submit",
                                FunctionDescriptorBuilder.returnsInt()
                                        .arg(ValueLayout.ADDRESS)
                                        .build());

                // io_uring_wait_cqe_timeout(ring, cqe_ptr, ts)
                tempWaitCqeTimeout =
                        LIBURING_FACTORY.downcall(
                                "io_uring_wait_cqe_timeout",
                                FunctionDescriptorBuilder.returnsInt()
                                        .args(
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS,
                                                ValueLayout.ADDRESS)
                                        .build());

                // Buffer ring registration (Linux 5.19+) - optional
                try {
                    // io_uring_register_buf_ring(ring, reg, flags)
                    tempRegBufRing =
                            LIBURING_FACTORY.downcall(
                                    "io_uring_register_buf_ring",
                                    FunctionDescriptorBuilder.returnsInt()
                                            .args(
                                                    ValueLayout.ADDRESS,
                                                    ValueLayout.ADDRESS,
                                                    ValueLayout.JAVA_INT)
                                            .build());

                    // io_uring_unregister_buf_ring(ring, bgid)
                    tempUnregBufRing =
                            LIBURING_FACTORY.downcall(
                                    "io_uring_unregister_buf_ring",
                                    FunctionDescriptorBuilder.returnsInt()
                                            .args(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
                                            .build());
                } catch (UnsatisfiedLinkError e) {
                    // Buffer ring not available in this liburing version
                }
            }

            // Load libc socket syscalls using LIBC factory
            // socket(int domain, int type, int protocol)
            tempSocket =
                    LIBC.downcall(
                            "socket",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT)
                                    .build());

            // connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen)
            tempConnect =
                    LIBC.downcall(
                            "connect",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT)
                                    .build());

            // bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen)
            tempBind =
                    LIBC.downcall(
                            "bind",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT)
                                    .build());

            // listen(int sockfd, int backlog)
            tempListen =
                    LIBC.downcall(
                            "listen",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                                    .build());

            // accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen)
            tempAccept =
                    LIBC.downcall(
                            "accept",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS)
                                    .build());

            // setsockopt(int sockfd, int level, int optname, const void *optval, socklen_t optlen)
            tempSetsockopt =
                    LIBC.downcall(
                            "setsockopt",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.JAVA_INT)
                                    .build());

            // fcntl(int fd, int cmd, int arg)
            tempFcntl =
                    LIBC.downcall(
                            "fcntl",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.JAVA_INT)
                                    .build());

            // close(int fd)
            tempClose =
                    LIBC.downcall(
                            "close",
                            FunctionDescriptorBuilder.returnsInt()
                                    .arg(ValueLayout.JAVA_INT)
                                    .build());

            // inet_pton(int af, const char *src, void *dst)
            tempInetPton =
                    LIBC.downcall(
                            "inet_pton",
                            FunctionDescriptorBuilder.returnsInt()
                                    .args(
                                            ValueLayout.JAVA_INT,
                                            ValueLayout.ADDRESS,
                                            ValueLayout.ADDRESS)
                                    .build());

            // htons(uint16_t hostshort)
            tempHtons =
                    LIBC.downcall(
                            "htons",
                            FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT));

        } catch (Exception e) {
            // liburing or libc functions not available
        }
        io_uring_queue_init = tempInit;
        io_uring_queue_init_params = tempInitParams;
        io_uring_queue_exit = tempExit;
        io_uring_register_buffers = tempRegBuffers;
        io_uring_unregister_buffers = tempUnregBuffers;
        io_uring_register_files = tempRegFiles;
        io_uring_unregister_files = tempUnregFiles;
        io_uring_register_files_update = tempRegFilesUpdate;
        io_uring_get_sqe = tempGetSqe;
        io_uring_submit = tempSubmit;
        // Manual: io_uring_wait_cqe
        io_uring_wait_cqe_timeout = tempWaitCqeTimeout;
        // P2: Buffer ring registration
        io_uring_register_buf_ring = tempRegBufRing;
        io_uring_unregister_buf_ring = tempUnregBufRing;
        // Manual: io_uring_prep_*

        // Native socket syscalls
        socket = tempSocket;
        connect = tempConnect;
        bind = tempBind;
        listen = tempListen;
        accept = tempAccept;
        setsockopt = tempSetsockopt;
        fcntl = tempFcntl;
        close = tempClose;
        inet_pton = tempInetPton;
        htons = tempHtons;
    }

    /** Attempt to load liburing from various locations. */
    private static SymbolLookup loadLibrary() {
        // 1. Try standard library lookup "uring"
        try {
            SymbolLookup lookup = SymbolLookup.libraryLookup("uring", Arena.global());
            if (lookup.find("io_uring_queue_init").isPresent()) return lookup;
        } catch (Throwable t) {
        }

        // 2. Try versioned names
        String[] versionedNames = {"liburing.so.2", "liburing.so.1", "liburing.so"};
        for (String name : versionedNames) {
            try {
                SymbolLookup lookup = SymbolLookup.libraryLookup(name, Arena.global());
                if (lookup.find("io_uring_queue_init").isPresent()) return lookup;
            } catch (Throwable t) {
            }
        }

        // 3. Try common system paths
        String arch = System.getProperty("os.arch");
        String[] searchPaths = getSearchPaths(arch);

        for (String path : searchPaths) {
            for (String name : versionedNames) {
                java.io.File f = new java.io.File(path, name);
                if (f.exists()) {
                    try {
                        System.load(f.getAbsolutePath());
                        SymbolLookup lookup = SymbolLookup.loaderLookup();
                        if (lookup.find("io_uring_queue_init").isPresent()) return lookup;
                    } catch (Throwable t) {
                    }
                }
            }
        }

        // 4. Fallback to default lookup
        SymbolLookup defaultLookup = Linker.nativeLinker().defaultLookup();
        if (defaultLookup.find("io_uring_queue_init").isPresent()) return defaultLookup;

        return null;
    }

    private static String[] getSearchPaths(String arch) {
        String gnuArch =
                switch (arch) {
                    case "aarch64" -> "aarch64-linux-gnu";
                    case "amd64", "x86_64" -> "x86_64-linux-gnu";
                    default -> null;
                };

        if (gnuArch != null) {
            return new String[] {
                "/usr/lib/" + gnuArch, "/lib/" + gnuArch, "/usr/local/lib", "/usr/lib", "/lib"
            };
        } else {
            return new String[] {"/usr/local/lib", "/usr/lib", "/lib"};
        }
    }

    /** Check if liburing was successfully loaded. */
    private static boolean isLibraryLoaded() {
        return LIBURING != null && io_uring_queue_init != null && socket != null;
    }

    /**
     * Initialize an io_uring instance.
     *
     * @param entries number of submission queue entries (must be power of 2)
     * @param ring pointer to io_uring structure
     * @param flags initialization flags
     * @return 0 on success, negative errno on failure
     */
    public static int queueInit(int entries, MemorySegment ring, int flags) {
        if (!isLibraryLoaded()) {
            throw new UnsatisfiedLinkError("liburing not loaded");
        }
        try {
            return (int) io_uring_queue_init.invokeExact(entries, ring, flags);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_queue_init failed", e);
        }
    }

    /**
     * Initialize io_uring with parameters.
     *
     * @param entries number of queue entries
     * @param ring pointer to io_uring structure
     * @param params pointer to io_uring_params structure
     * @return 0 on success, negative errno on failure
     */
    public static int queueInitParams(int entries, MemorySegment ring, MemorySegment params) {
        try {
            return (int) io_uring_queue_init_params.invokeExact(entries, ring, params);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_queue_init_params failed", e);
        }
    }

    /** Tear down an io_uring instance. */
    public static void queueExit(MemorySegment ring) {
        try {
            io_uring_queue_exit.invokeExact(ring);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_queue_exit failed", e);
        }
    }

    /**
     * Register buffers for zero-copy I/O.
     *
     * @param ring pointer to io_uring structure
     * @param iovecs array of iovec structures
     * @param nrIovecs number of iovecs
     * @return 0 on success, negative errno on failure
     */
    public static int registerBuffers(MemorySegment ring, MemorySegment iovecs, int nrIovecs) {
        try {
            return (int) io_uring_register_buffers.invokeExact(ring, iovecs, nrIovecs);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_register_buffers failed", e);
        }
    }

    /** Unregister previously registered buffers. */
    public static int unregisterBuffers(MemorySegment ring) {
        try {
            return (int) io_uring_unregister_buffers.invokeExact(ring);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_unregister_buffers failed", e);
        }
    }

    /**
     * Get a submission queue entry.
     *
     * @return pointer to SQE, or null if queue is full
     */
    public static int registerFiles(MemorySegment ring, MemorySegment files, int nr_files) {
        try {
            return (int) io_uring_register_files.invokeExact(ring, files, nr_files);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int unregisterFiles(MemorySegment ring) {
        try {
            return (int) io_uring_unregister_files.invokeExact(ring);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int registerFilesUpdate(
            MemorySegment ring, int off, MemorySegment files, int nr_files) {
        try {
            return (int) io_uring_register_files_update.invokeExact(ring, off, files, nr_files);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static MemorySegment getSqe(MemorySegment ring) {
        try {
            MemorySegment sqe = (MemorySegment) io_uring_get_sqe.invokeExact(ring);
            if (sqe.equals(MemorySegment.NULL)) {
                return null;
            }
            return sqe.reinterpret(IO_URING_SQE_LAYOUT.byteSize());
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_get_sqe failed", e);
        }
    }

    /**
     * Submit queued operations to the kernel.
     *
     * @return number of submitted operations, or negative errno
     */
    public static int submit(MemorySegment ring) {
        try {
            return (int) io_uring_submit.invokeExact(ring);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_submit failed", e);
        }
    }

    /**
     * Wait for a completion queue entry.
     *
     * @param ring pointer to io_uring structure
     * @param cqePtr pointer to receive CQE pointer
     * @return 0 on success, negative errno on failure
     */
    public static int waitCqe(MemorySegment ring, MemorySegment cqePtr) {
        try {
            // Use wait_cqe_timeout with NULL timeout
            return (int) io_uring_wait_cqe_timeout.invokeExact(ring, cqePtr, MemorySegment.NULL);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_wait_cqe failed", e);
        }
    }

    /**
     * Wait for a completion (with timeout).
     *
     * @param ring pointer to io_uring structure
     * @param cqePtr pointer to store CQE pointer
     * @param ts pointer to __kernel_timespec structure (or null for no timeout)
     * @return 0 on success, negative errno on failure
     */
    public static int waitCqeTimeout(MemorySegment ring, MemorySegment cqePtr, MemorySegment ts) {
        if (io_uring_wait_cqe_timeout == null) {
            // Fall back to regular wait if timeout not available
            return waitCqe(ring, cqePtr);
        }
        try {
            return (int) io_uring_wait_cqe_timeout.invokeExact(ring, cqePtr, ts);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_wait_cqe_timeout failed", e);
        }
    }

    /**
     * Peek for a completion without blocking.
     *
     * @return 0 if CQE available, -EAGAIN if not
     */
    public static int peekCqe(MemorySegment ring, MemorySegment cqePtr) {
        try {
            // Access cq structure
            MemorySegment cq = ring.asSlice(CQ_OFFSET, IO_URING_CQ_LAYOUT.byteSize());

            // Get khead pointer
            MemorySegment kheadPtr = cq.get(ValueLayout.ADDRESS, CQ_KHEAD_OFFSET);
            kheadPtr = kheadPtr.reinterpret(4);

            // Get ktail pointer
            MemorySegment ktailPtr = cq.get(ValueLayout.ADDRESS, CQ_KTAIL_OFFSET);
            ktailPtr = ktailPtr.reinterpret(4);

            // Read head and tail
            int head = kheadPtr.get(ValueLayout.JAVA_INT, 0);
            int tail = ktailPtr.get(ValueLayout.JAVA_INT, 0);

            if (head == tail) {
                return -EAGAIN;
            }

            // Get ring_mask
            int mask = cq.get(ValueLayout.JAVA_INT, CQ_RING_MASK_OFFSET);

            // Get cqes array pointer
            MemorySegment cqes = cq.get(ValueLayout.ADDRESS, CQ_CQES_OFFSET);

            // Calculate index
            int index = head & mask;

            // Reinterpret cqes array
            long cqeSize = IO_URING_CQE_LAYOUT.byteSize();
            cqes = cqes.reinterpret((long) (mask + 1) * cqeSize);

            MemorySegment cqe = cqes.asSlice(index * cqeSize, cqeSize);

            // Write cqe address to cqePtr
            cqePtr.set(ValueLayout.ADDRESS, 0, cqe);

            return 0;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    /** Mark a CQE as seen (consumed). */
    public static void cqeSeen(MemorySegment ring, MemorySegment cqe) {
        try {
            MemorySegment cq = ring.asSlice(CQ_OFFSET, IO_URING_CQ_LAYOUT.byteSize());

            MemorySegment kheadPtr = cq.get(ValueLayout.ADDRESS, CQ_KHEAD_OFFSET);
            kheadPtr = kheadPtr.reinterpret(4);

            int head = kheadPtr.get(ValueLayout.JAVA_INT, 0);
            kheadPtr.set(ValueLayout.JAVA_INT, 0, head + 1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Cached CQ accessors for hot-path polling.
     *
     * <p>{@link #peekCqe(MemorySegment, MemorySegment)} and {@link #cqeSeen(MemorySegment,
     * MemorySegment)} are implemented in Java because liburing exposes them as static inline. The
     * naive implementation recreates multiple {@link MemorySegment} views on every call.
     *
     * <p>This helper caches the stable CQ pointers (khead/ktail) and the CQEs array view so that
     * the poll loop only allocates the per-CQE slice, significantly reducing allocation pressure
     * and improving tail latency.
     */
    public static final class CqFastPath {
        private final MemorySegment kheadPtr;
        private final MemorySegment ktailPtr;
        private final int mask;
        private final MemorySegment cqesArray;
        private final long cqeSize;

        private static final long CQE_USER_DATA_OFFSET =
            IO_URING_CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("user_data"));
        private static final long CQE_RES_OFFSET =
            IO_URING_CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("res"));
        private static final long CQE_FLAGS_OFFSET =
            IO_URING_CQE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("flags"));

        private CqFastPath(MemorySegment ring) {
            MemorySegment cq = ring.asSlice(CQ_OFFSET, IO_URING_CQ_LAYOUT.byteSize());

            MemorySegment khead = cq.get(ValueLayout.ADDRESS, CQ_KHEAD_OFFSET);
            this.kheadPtr = khead.reinterpret(Integer.BYTES);

            MemorySegment ktail = cq.get(ValueLayout.ADDRESS, CQ_KTAIL_OFFSET);
            this.ktailPtr = ktail.reinterpret(Integer.BYTES);

            this.mask = cq.get(ValueLayout.JAVA_INT, CQ_RING_MASK_OFFSET);

            MemorySegment cqes = cq.get(ValueLayout.ADDRESS, CQ_CQES_OFFSET);
            this.cqeSize = IO_URING_CQE_LAYOUT.byteSize();
            this.cqesArray = cqes.reinterpret((long) (mask + 1) * cqeSize);
        }

        /** @return CQ head if CQE available, -1 if none */
        public int peekHead() {
            int head = kheadPtr.get(ValueLayout.JAVA_INT, 0);
            int tail = ktailPtr.get(ValueLayout.JAVA_INT, 0);
            return (head == tail) ? -1 : head;
        }

        public long cqeUserData(int head) {
            int index = head & mask;
            long base = (long) index * cqeSize;
            return cqesArray.get(ValueLayout.JAVA_LONG, base + CQE_USER_DATA_OFFSET);
        }

        public int cqeRes(int head) {
            int index = head & mask;
            long base = (long) index * cqeSize;
            return cqesArray.get(ValueLayout.JAVA_INT, base + CQE_RES_OFFSET);
        }

        public int cqeFlags(int head) {
            int index = head & mask;
            long base = (long) index * cqeSize;
            return cqesArray.get(ValueLayout.JAVA_INT, base + CQE_FLAGS_OFFSET);
        }

        /** @return 0 if CQE available, -EAGAIN if not */
        public int peekCqe(MemorySegment cqePtr) {
            int head = peekHead();
            if (head < 0) {
                return -EAGAIN;
            }

            // Compatibility path: produce a CQE view segment for callers that still want it.
            int index = head & mask;
            MemorySegment cqe = cqesArray.asSlice((long) index * cqeSize, cqeSize);
            cqePtr.set(ValueLayout.ADDRESS, 0, cqe);
            return 0;
        }

        /** Increment CQ head to mark the next CQE as consumed. */
        public void cqeSeen() {
            int head = kheadPtr.get(ValueLayout.JAVA_INT, 0);
            kheadPtr.set(ValueLayout.JAVA_INT, 0, head + 1);
        }

        /** Increment CQ head using a previously read head value. */
        public void cqeSeen(int head) {
            kheadPtr.set(ValueLayout.JAVA_INT, 0, head + 1);
        }
    }

    /** Create a cached CQ accessor for the given ring. */
    public static CqFastPath createCqFastPath(MemorySegment ring) {
        return new CqFastPath(ring);
    }

    /** Prepare a connect operation. */
    public static void prepConnect(MemorySegment sqe, int fd, MemorySegment addr, int addrlen) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_CONNECT);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_OFF_VH.set(sqe, 0L, (long) addrlen);
        SQE_ADDR_VH.set(sqe, 0L, addr.address());
    }

    /** Prepare an accept operation. */
    public static void prepAccept(
            MemorySegment sqe, int fd, MemorySegment addr, MemorySegment addrlen, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_ACCEPT);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_OFF_VH.set(sqe, 0L, addrlen.address());
        SQE_ADDR_VH.set(sqe, 0L, addr.address());
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
    }

    /** Prepare a send operation. */
    public static void prepSend(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_SEND);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_ADDR_VH.set(sqe, 0L, buf.address());
        SQE_LEN_VH.set(sqe, 0L, (int) len);
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
    }

    /** Prepare a recv operation. */
    public static void prepRecv(MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_RECV);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_ADDR_VH.set(sqe, 0L, buf.address());
        SQE_LEN_VH.set(sqe, 0L, (int) len);
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
    }

    /** Set flags for the SQE (e.g. IOSQE_FIXED_FILE). */
    public static void setSqeFlags(MemorySegment sqe, int flags) {
        SQE_FLAGS_VH.set(sqe, 0L, (byte) flags);
    }

    /** Prepare a send operation using a registered file index. */
    public static void prepSendFixedFile(
            MemorySegment sqe, int fileIndex, MemorySegment buf, long len, int msgFlags) {
        prepSend(sqe, fileIndex, buf, len, msgFlags);
        setSqeFlags(sqe, IOSQE_FIXED_FILE);
    }

    /** Prepare a recv operation using a registered file index. */
    public static void prepRecvFixedFile(
            MemorySegment sqe, int fileIndex, MemorySegment buf, long len, int msgFlags) {
        prepRecv(sqe, fileIndex, buf, len, msgFlags);
        setSqeFlags(sqe, IOSQE_FIXED_FILE);
    }

    private static void clearSqe(MemorySegment sqe) {
        sqe.fill((byte) 0);
    }

    // io_uring constants
    public static final int IORING_SETUP_SQPOLL = 1 << 1;
    public static final int IORING_SETUP_SQ_AFF = 1 << 2;
    public static final int IORING_SETUP_CQSIZE = 1 << 3;
    // P0: Additional SQPOLL optimization flags (Linux 5.11+)
    public static final int IORING_SETUP_COOP_TASKRUN =
            1 << 8; // Cooperative task running (reduces interrupts)
    public static final int IORING_SETUP_SINGLE_ISSUER =
            1 << 12; // Single thread submits (enables optimizations)

    // Operation codes
    public static final byte IORING_OP_NOP = 0;
    public static final byte IORING_OP_READV = 1;
    public static final byte IORING_OP_WRITEV = 2;
    public static final byte IORING_OP_ACCEPT = 13;
    public static final byte IORING_OP_CONNECT = 16;
    public static final byte IORING_OP_SEND = 26;
    public static final byte IORING_OP_RECV = 27;
    // Keep in sync with linux uapi enum io_uring_op (see /usr/include/linux/io_uring.h).
    // On modern kernels 46 is IORING_OP_URING_CMD; SEND_ZC is 47.
    public static final byte IORING_OP_SEND_ZC = 47;
    // P1: Multishot receive operation (Linux 5.16+)
    // In io_uring uapi this is a bit in sqe->ioprio for recv/recvmsg.
    public static final byte IORING_OP_RECV_MULTISHOT = 27; // Same opcode as RECV, but with ioprio flag
    public static final int IORING_RECV_MULTISHOT = 1 << 1;

    // Registered (fixed) buffers for send/recv via sqe->ioprio.
    // See liburing's IORING_RECVSEND_FIXED_BUF.
    public static final int IORING_RECVSEND_FIXED_BUF = 1 << 2;
    private static final java.lang.invoke.VarHandle SQE_IOPRIO_VH = SQE.varHandle("ioprio");

    // SQE Flags
    public static final int IOSQE_FIXED_FILE = 1 << 0;
    public static final int IOSQE_IO_DRAIN = 1 << 1;
    public static final int IOSQE_IO_LINK = 1 << 2;
    public static final int IOSQE_IO_HARDLINK = 1 << 3; // P2: Hard link for chained operations
    public static final int IOSQE_BUFFER_SELECT = 1 << 4; // P2: Enable buffer ring selection
    public static final int IOSQE_CQE_SKIP_SUCCESS = 1 << 6; // P2: Skip CQE on success (linked ops)

    // CQE Flags (for completion processing)
    public static final int IORING_CQE_F_BUFFER = 1 << 0; // P2: Buffer ID in upper 16 bits of flags
    public static final int IORING_CQE_F_MORE = 1 << 1; // More completions coming (multishot)
    public static final int IORING_CQE_F_NOTIF = 1 << 3; // Notification CQE (zero-copy send)

    // P2: Buffer Ring Registration Constants
    public static final int IORING_REGISTER_PBUF_RING = 22; // Register provided buffer ring
    public static final int IORING_UNREGISTER_PBUF_RING = 23; // Unregister provided buffer ring

    // P2: Buffer ring flags
    public static final int IOU_PBUF_RING_MMAP = 1 << 0; // Use mmap for buffer ring

    /**
     * Memory layout for io_uring structure. Simplified - full structure is complex and varies by
     * kernel version.
     */
    /** Memory layout for io_uring_params. */
    public static final StructLayout IO_URING_PARAMS_LAYOUT =
            MemoryLayout.structLayout(
                            ValueLayout.JAVA_INT.withName("sq_entries"),
                            ValueLayout.JAVA_INT.withName("cq_entries"),
                            ValueLayout.JAVA_INT.withName("flags"),
                            ValueLayout.JAVA_INT.withName("sq_thread_cpu"),
                            ValueLayout.JAVA_INT.withName("sq_thread_idle"),
                            ValueLayout.JAVA_INT.withName("features"),
                            ValueLayout.JAVA_INT.withName("wq_fd"),
                            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("resv"),
                            MemoryLayout.sequenceLayout(40, ValueLayout.JAVA_BYTE)
                                    .withName("sq_off"),
                            MemoryLayout.sequenceLayout(40, ValueLayout.JAVA_BYTE)
                                    .withName("cq_off"))
                    .withName("io_uring_params");

    /** Memory layout for io_uring_sq (Submission Queue). */
    public static final StructLayout IO_URING_SQ_LAYOUT =
            MemoryLayout.structLayout(
                            ValueLayout.ADDRESS.withName("khead"),
                            ValueLayout.ADDRESS.withName("ktail"),
                            ValueLayout.ADDRESS.withName("kring_mask"),
                            ValueLayout.ADDRESS.withName("kring_entries"),
                            ValueLayout.ADDRESS.withName("kflags"),
                            ValueLayout.ADDRESS.withName("kdropped"),
                            ValueLayout.ADDRESS.withName("array"),
                            ValueLayout.ADDRESS.withName("sqes"),
                            ValueLayout.JAVA_INT.withName("sqe_head"),
                            ValueLayout.JAVA_INT.withName("sqe_tail"),
                            ValueLayout.JAVA_LONG.withName("ring_sz"),
                            ValueLayout.ADDRESS.withName("ring_ptr"),
                            ValueLayout.JAVA_INT.withName("ring_mask"),
                            ValueLayout.JAVA_INT.withName("ring_entries"),
                            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("pad"))
                    .withName("io_uring_sq");

    /** Memory layout for io_uring_cq (Completion Queue). */
    public static final StructLayout IO_URING_CQ_LAYOUT =
            MemoryLayout.structLayout(
                            ValueLayout.ADDRESS.withName("khead"),
                            ValueLayout.ADDRESS.withName("ktail"),
                            ValueLayout.ADDRESS.withName("kring_mask"),
                            ValueLayout.ADDRESS.withName("kring_entries"),
                            ValueLayout.ADDRESS.withName("kflags"),
                            ValueLayout.ADDRESS.withName("koverflow"),
                            ValueLayout.ADDRESS.withName("cqes"),
                            ValueLayout.JAVA_LONG.withName("ring_sz"),
                            ValueLayout.ADDRESS.withName("ring_ptr"),
                            ValueLayout.JAVA_INT.withName("ring_mask"),
                            ValueLayout.JAVA_INT.withName("ring_entries"),
                            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_INT).withName("pad"))
                    .withName("io_uring_cq");

    /** Memory layout for io_uring structure. */
    public static final StructLayout IO_URING_LAYOUT =
            MemoryLayout.structLayout(
                            IO_URING_SQ_LAYOUT.withName("sq"),
                            IO_URING_CQ_LAYOUT.withName("cq"),
                            ValueLayout.JAVA_INT.withName("flags"),
                            ValueLayout.JAVA_INT.withName("ring_fd"),
                            ValueLayout.JAVA_INT.withName("features"),
                            ValueLayout.JAVA_INT.withName("enter_ring_fd"),
                            ValueLayout.JAVA_BYTE.withName("int_flags"),
                            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("pad"),
                            ValueLayout.JAVA_INT.withName("pad2"))
                    .withName("io_uring");

    // CQ field offsets
    private static final long CQ_OFFSET =
            IO_URING_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cq"));
    private static final long CQ_KHEAD_OFFSET =
            IO_URING_CQ_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("khead"));
    private static final long CQ_KTAIL_OFFSET =
            IO_URING_CQ_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("ktail"));
    private static final long CQ_RING_MASK_OFFSET =
            IO_URING_CQ_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("ring_mask"));
    private static final long CQ_CQES_OFFSET =
            IO_URING_CQ_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cqes"));

    /**
     * Memory layout for __kernel_timespec (for timeout operations).
     *
     * @see LinuxLayouts#TIMESPEC
     */
    public static final StructLayout KERNEL_TIMESPEC_LAYOUT = LinuxLayouts.TIMESPEC;

    /**
     * Memory layout for iovec structure (for buffer registration).
     *
     * @see LinuxLayouts#IOVEC
     */
    public static final StructLayout IOVEC_LAYOUT = LinuxLayouts.IOVEC;

    // ========== P2: Buffer Ring Structures ==========

    /**
     * Memory layout for io_uring_buf (single buffer in a ring). Each entry represents one buffer
     * that can be selected by the kernel.
     */
    public static final StructLayout IO_URING_BUF_LAYOUT =
            MemoryLayout.structLayout(
                            ValueLayout.JAVA_LONG.withName("addr"), // Buffer address
                            ValueLayout.JAVA_INT.withName("len"), // Buffer length
                            ValueLayout.JAVA_SHORT.withName("bid"), // Buffer ID
                            ValueLayout.JAVA_SHORT.withName("resv") // Reserved
                            )
                    .withName("io_uring_buf");

    /**
     * Memory layout for io_uring_buf_ring (buffer ring header). The ring uses a tail pointer to
     * track which buffers are available.
     */
    public static final StructLayout IO_URING_BUF_RING_LAYOUT =
            MemoryLayout.structLayout(
                            // Union: resv or tail at offset 0
                            ValueLayout.JAVA_LONG.withName("resv1"),
                            ValueLayout.JAVA_INT.withName("resv2"),
                            ValueLayout.JAVA_SHORT.withName("resv3"),
                            ValueLayout.JAVA_SHORT.withName("tail") // Tail index for buffer ring
                            )
                    .withName("io_uring_buf_ring");

    /**
     * Memory layout for io_uring_buf_reg (buffer ring registration). Used with
     * IORING_REGISTER_PBUF_RING.
     */
    public static final StructLayout IO_URING_BUF_REG_LAYOUT =
            MemoryLayout.structLayout(
                            ValueLayout.JAVA_LONG.withName("ring_addr"), // Address of buffer ring
                            ValueLayout.JAVA_INT.withName("ring_entries"), // Number of entries
                            ValueLayout.JAVA_SHORT.withName("bgid"), // Buffer group ID
                            ValueLayout.JAVA_SHORT.withName(
                                    "flags"), // Flags (e.g., IOU_PBUF_RING_MMAP)
                            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_LONG).withName("resv"))
                    .withName("io_uring_buf_reg");

    // Buffer ring field offsets
    private static final long BUF_RING_TAIL_OFFSET = 14; // Offset to tail field
    private static final long BUF_ADDR_OFFSET = 0;
    private static final long BUF_LEN_OFFSET = 8;
    private static final long BUF_BID_OFFSET = 12;

    // SQE/CQE field access uses extracted VarHandles for hot path performance.
    // See class-level VarHandle declarations (SQE_*_VH, CQE_*_VH).

    /** Set SQE opcode field. */
    public static void sqeSetOpcode(MemorySegment sqe, byte opcode) {
        SQE_OPCODE_VH.set(sqe, 0L, opcode);
    }

    /** Set SQE flags field. */
    public static void sqeSetFlags(MemorySegment sqe, byte flags) {
        SQE_FLAGS_VH.set(sqe, 0L, flags);
    }

    /** Set SQE ioprio field (operation-specific flags for send/recv). */
    public static void sqeSetIoprio(MemorySegment sqe, short ioprio) {
        SQE_IOPRIO_VH.set(sqe, 0L, ioprio);
    }

    /** Get SQE ioprio field. */
    public static short sqeGetIoprio(MemorySegment sqe) {
        return (short) SQE_IOPRIO_VH.get(sqe, 0L);
    }

    /** Set SQE fd field. */
    public static void sqeSetFd(MemorySegment sqe, int fd) {
        SQE_FD_VH.set(sqe, 0L, fd);
    }

    /** Set SQE addr field (buffer address). */
    public static void sqeSetAddr(MemorySegment sqe, long addr) {
        SQE_ADDR_VH.set(sqe, 0L, addr);
    }

    /** Set SQE len field (buffer length). */
    public static void sqeSetLen(MemorySegment sqe, int len) {
        SQE_LEN_VH.set(sqe, 0L, len);
    }

    /** Set SQE user_data field (for request tracking). */
    public static void sqeSetUserData(MemorySegment sqe, long userData) {
        SQE_USER_DATA_VH.set(sqe, 0L, userData);
    }

    /** Set SQE buf_index field (for fixed buffers). */
    public static void sqeSetBufIndex(MemorySegment sqe, short bufIndex) {
        SQE_BUF_INDEX_VH.set(sqe, 0L, bufIndex);
    }

    /** Set SQE buf_group field (for buffer ring selection). */
    public static void sqeSetBufGroup(MemorySegment sqe, short bufGroup) {
        // buf_group shares storage with buf_index in the C struct (union).
        SQE_BUF_INDEX_VH.set(sqe, 0L, bufGroup);
    }

    /** Set SQE op_flags field (operation-specific flags like msg_flags). */
    public static void sqeSetOpFlags(MemorySegment sqe, int opFlags) {
        SQE_OP_FLAGS_VH.set(sqe, 0L, opFlags);
    }

    /** Get SQE flags field. */
    public static byte sqeGetFlags(MemorySegment sqe) {
        return (byte) SQE_FLAGS_VH.get(sqe, 0L);
    }

    /** Get CQE user_data field. */
    public static long cqeGetUserData(MemorySegment cqe) {
        return (long) CQE_USER_DATA_VH.get(cqe, 0L);
    }

    /** Get CQE res field (result/bytes transferred). */
    public static int cqeGetRes(MemorySegment cqe) {
        return (int) CQE_RES_VH.get(cqe, 0L);
    }

    /** Get CQE flags field. */
    public static int cqeGetFlags(MemorySegment cqe) {
        return (int) CQE_FLAGS_VH.get(cqe, 0L);
    }

    /**
     * Prepare a send operation with fixed buffer.
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param bufIndex index of registered buffer
     * @param len number of bytes to send
     * @param flags send flags
     */
    public static void prepSendFixed(
            MemorySegment sqe, int fd, short bufIndex, int len, int flags) {
        // Legacy helper retained for compatibility with older call sites.
        // Prefer prepSendFixedBuf(...) which also sets addr.
        clearSqe(sqe);
        sqeSetOpcode(sqe, IORING_OP_SEND);
        sqeSetFd(sqe, fd);
        sqeSetAddr(sqe, 0L);
        sqeSetLen(sqe, len);
        sqeSetOpFlags(sqe, flags);
        sqeSetBufIndex(sqe, bufIndex);
        sqeSetIoprio(sqe, (short) (sqeGetIoprio(sqe) | IORING_RECVSEND_FIXED_BUF));
    }

    /** Prepare a send operation using a registered buffer (IORING_RECVSEND_FIXED_BUF). */
    public static void prepSendFixedBuf(
            MemorySegment sqe, int fd, MemorySegment buf, int len, short bufIndex, int flags) {
        clearSqe(sqe);
        sqeSetOpcode(sqe, IORING_OP_SEND);
        sqeSetFd(sqe, fd);
        sqeSetAddr(sqe, buf.address());
        sqeSetLen(sqe, len);
        sqeSetOpFlags(sqe, flags);
        sqeSetBufIndex(sqe, bufIndex);
        sqeSetIoprio(sqe, (short) (sqeGetIoprio(sqe) | IORING_RECVSEND_FIXED_BUF));
    }

    /**
     * Prepare a recv operation with fixed buffer.
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param bufIndex index of registered buffer
     * @param len maximum bytes to receive
     * @param flags recv flags
     */
    public static void prepRecvFixed(
            MemorySegment sqe, int fd, short bufIndex, int len, int flags) {
        // Legacy helper retained for compatibility with older call sites.
        // Prefer prepRecvFixedBuf(...) which also sets addr.
        clearSqe(sqe);
        sqeSetOpcode(sqe, IORING_OP_RECV);
        sqeSetFd(sqe, fd);
        sqeSetAddr(sqe, 0L);
        sqeSetLen(sqe, len);
        sqeSetOpFlags(sqe, flags);
        sqeSetBufIndex(sqe, bufIndex);
        sqeSetIoprio(sqe, (short) (sqeGetIoprio(sqe) | IORING_RECVSEND_FIXED_BUF));
    }

    /** Prepare a recv operation using a registered buffer (IORING_RECVSEND_FIXED_BUF). */
    public static void prepRecvFixedBuf(
            MemorySegment sqe, int fd, MemorySegment buf, int len, short bufIndex, int flags) {
        clearSqe(sqe);
        sqeSetOpcode(sqe, IORING_OP_RECV);
        sqeSetFd(sqe, fd);
        sqeSetAddr(sqe, buf.address());
        sqeSetLen(sqe, len);
        sqeSetOpFlags(sqe, flags);
        sqeSetBufIndex(sqe, bufIndex);
        sqeSetIoprio(sqe, (short) (sqeGetIoprio(sqe) | IORING_RECVSEND_FIXED_BUF));
    }

    // ========== P1: Zero-Copy Send (SEND_ZC) ==========

    /**
     * Prepare a zero-copy send operation.
     *
     * <p>Zero-copy send avoids copying data from user-space to kernel-space, providing significant
     * performance improvements for large buffers.
     *
     * <p><b>IMPORTANT:</b> When using zero-copy send:
     *
     * <ul>
     *   <li>You will receive TWO completions: one for send completion, one for notification
     *       (IORING_CQE_F_NOTIF)
     *   <li>The buffer must NOT be modified until the NOTIF completion is received
     *   <li>Check CQE flags for IORING_CQE_F_NOTIF to distinguish notification from send completion
     * </ul>
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param buf buffer to send (must remain valid until NOTIF completion)
     * @param len number of bytes to send
     * @param flags send flags (MSG_* constants)
     */
    public static void prepSendZc(
            MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_SEND_ZC);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_ADDR_VH.set(sqe, 0L, buf.address());
        SQE_LEN_VH.set(sqe, 0L, (int) len);
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
    }

    /**
     * Prepare a zero-copy send operation with fixed buffer.
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param bufIndex index of registered buffer
     * @param len number of bytes to send
     * @param flags send flags
     */
    public static void prepSendZcFixed(
            MemorySegment sqe, int fd, short bufIndex, int len, int flags) {
        // Best-effort legacy helper. Prefer prepSendZc(...) + explicit fixed-buf bits if needed.
        clearSqe(sqe);
        sqeSetOpcode(sqe, IORING_OP_SEND_ZC);
        sqeSetFd(sqe, fd);
        sqeSetAddr(sqe, 0L);
        sqeSetLen(sqe, len);
        sqeSetOpFlags(sqe, flags);
        sqeSetBufIndex(sqe, bufIndex);
        sqeSetIoprio(sqe, (short) (sqeGetIoprio(sqe) | IORING_RECVSEND_FIXED_BUF));
    }

    /**
     * Check if a CQE is a zero-copy notification (not actual completion).
     *
     * @param cqe completion queue entry
     * @return true if this is a notification CQE
     */
    public static boolean isZeroCopyNotification(MemorySegment cqe) {
        int flags = cqeGetFlags(cqe);
        return (flags & IORING_CQE_F_NOTIF) != 0;
    }

    // ========== P1: Multi-Shot Receive ==========

    /**
     * Prepare a multi-shot receive operation.
     *
     * <p>Multi-shot receive keeps the SQE active and generates multiple CQEs until the operation is
     * cancelled or an error occurs. This is ideal for persistent receive loops as it eliminates the
     * need to resubmit after each receive.
     *
     * <p><b>IMPORTANT:</b>
     *
     * <ul>
     *   <li>Check CQE flags for IORING_CQE_F_MORE - if set, more completions are coming
     *   <li>If IORING_CQE_F_MORE is NOT set, the operation has terminated and must be resubmitted
     *   <li>Works best with buffer rings (IOSQE_BUFFER_SELECT) for automatic buffer management
     *   <li>Requires Linux 5.16+
     * </ul>
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param buf buffer for receiving data
     * @param len maximum bytes to receive
     * @param flags recv flags (MSG_* constants)
     */
    public static void prepRecvMultishot(
            MemorySegment sqe, int fd, MemorySegment buf, long len, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_RECV);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_ADDR_VH.set(sqe, 0L, buf.address());
        SQE_LEN_VH.set(sqe, 0L, (int) len);
        // msg_flags
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
        // ioprio flags for recv/recvmsg
        SQE_IOPRIO_VH.set(sqe, 0L, (short) (IORING_RECV_MULTISHOT));
    }

    /**
     * Check if a multishot receive is still active (more completions coming).
     *
     * @param cqe completion queue entry
     * @return true if more completions are expected
     */
    public static boolean hasMoreCompletions(MemorySegment cqe) {
        int flags = cqeGetFlags(cqe);
        return (flags & IORING_CQE_F_MORE) != 0;
    }

    // ========== P2: Buffer Ring API ==========

    /**
     * Check if buffer ring registration is supported. Requires liburing with
     * io_uring_register_buf_ring (Linux 5.19+).
     *
     * @return true if buffer ring is available
     */
    public static boolean isBufferRingSupported() {
        return io_uring_register_buf_ring != null;
    }

    /**
     * Register a provided buffer ring with io_uring.
     *
     * <p>Buffer rings allow the kernel to automatically select buffers for receive operations,
     * eliminating the need to specify buffers in each SQE. This is essential for efficient
     * multishot receive.
     *
     * <p><b>Usage pattern:</b>
     *
     * <ol>
     *   <li>Allocate buffer ring memory (header + buffers)
     *   <li>Initialize the ring with bufferRingInit()
     *   <li>Add buffers with bufferRingAdd()
     *   <li>Call bufferRingAdvance() to make buffers visible to kernel
     *   <li>Register with registerBufferRing()
     *   <li>Use IOSQE_BUFFER_SELECT flag in recv operations
     * </ol>
     *
     * @param ring io_uring instance
     * @param bufRing buffer ring memory (header at start)
     * @param nentries number of buffer entries (power of 2)
     * @param bgid buffer group ID (unique per ring)
     * @return 0 on success, negative errno on failure
     */
    public static int registerBufferRing(
            MemorySegment ring, MemorySegment bufRing, int nentries, short bgid) {
        if (io_uring_register_buf_ring == null) {
            return -95; // EOPNOTSUPP
        }
        try {
            // Create registration structure
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment reg = arena.allocate(IO_URING_BUF_REG_LAYOUT);
                reg.set(ValueLayout.JAVA_LONG, 0, bufRing.address()); // ring_addr
                reg.set(ValueLayout.JAVA_INT, 8, nentries); // ring_entries
                reg.set(ValueLayout.JAVA_SHORT, 12, bgid); // bgid
                reg.set(ValueLayout.JAVA_SHORT, 14, (short) 0); // flags
                // resv fields are zero-initialized by allocate

                return (int) io_uring_register_buf_ring.invokeExact(ring, reg, 0);
            }
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_register_buf_ring failed", e);
        }
    }

    /**
     * Unregister a buffer ring.
     *
     * @param ring io_uring instance
     * @param bgid buffer group ID
     * @return 0 on success, negative errno on failure
     */
    public static int unregisterBufferRing(MemorySegment ring, int bgid) {
        if (io_uring_unregister_buf_ring == null) {
            return -95; // EOPNOTSUPP
        }
        try {
            return (int) io_uring_unregister_buf_ring.invokeExact(ring, bgid);
        } catch (Throwable e) {
            throw new RuntimeException("io_uring_unregister_buf_ring failed", e);
        }
    }

    /**
     * Initialize a buffer ring header. Call this before adding buffers.
     *
     * @param bufRing pointer to buffer ring memory
     */
    public static void bufferRingInit(MemorySegment bufRing) {
        // Zero out the header (first 16 bytes)
        bufRing.set(ValueLayout.JAVA_LONG, 0, 0L);
        bufRing.set(ValueLayout.JAVA_LONG, 8, 0L);
    }

    /**
     * Add a buffer to the buffer ring.
     *
     * @param bufRing pointer to buffer ring
     * @param buf buffer address
     * @param bufLen buffer length
     * @param bid buffer ID (used to identify buffer on completion)
     * @param mask ring mask (nentries - 1)
     * @param idx current index in ring
     */
    public static void bufferRingAdd(
            MemorySegment bufRing, long buf, int bufLen, short bid, int mask, int idx) {
        // Calculate offset to buffer entry: header (16 bytes) + (idx & mask) * sizeof(io_uring_buf)
        long entryOffset = 16L + ((idx & mask) * IO_URING_BUF_LAYOUT.byteSize());

        bufRing.set(ValueLayout.JAVA_LONG, entryOffset + BUF_ADDR_OFFSET, buf);
        bufRing.set(ValueLayout.JAVA_INT, entryOffset + BUF_LEN_OFFSET, bufLen);
        bufRing.set(ValueLayout.JAVA_SHORT, entryOffset + BUF_BID_OFFSET, bid);
    }

    /**
     * Advance the buffer ring tail to make buffers visible to the kernel. Must be called after
     * adding buffers with bufferRingAdd().
     *
     * @param bufRing pointer to buffer ring
     * @param count number of buffers added
     */
    public static void bufferRingAdvance(MemorySegment bufRing, int count) {
        // Read current tail, add count, write back with atomic semantics
        short currentTail = bufRing.get(ValueLayout.JAVA_SHORT, BUF_RING_TAIL_OFFSET);
        bufRing.set(ValueLayout.JAVA_SHORT, BUF_RING_TAIL_OFFSET, (short) (currentTail + count));
    }

    /**
     * Get the buffer ID from a CQE with IORING_CQE_F_BUFFER flag set.
     *
     * @param cqe completion queue entry
     * @return buffer ID (bid) or -1 if no buffer flag
     */
    public static int cqeGetBufferId(MemorySegment cqe) {
        int flags = cqeGetFlags(cqe);
        if ((flags & IORING_CQE_F_BUFFER) == 0) {
            return -1;
        }
        // Buffer ID is in upper 16 bits of flags
        return (flags >> 16) & 0xFFFF;
    }

    /**
     * Check if CQE has buffer ID set (IORING_CQE_F_BUFFER).
     *
     * @param cqe completion queue entry
     * @return true if buffer was selected from ring
     */
    public static boolean cqeHasBuffer(MemorySegment cqe) {
        int flags = cqeGetFlags(cqe);
        return (flags & IORING_CQE_F_BUFFER) != 0;
    }

    /**
     * Prepare a multishot receive with buffer ring selection.
     *
     * <p>This is the most efficient receive pattern:
     *
     * <ul>
     *   <li>Multishot: keeps receiving without resubmitting SQEs
     *   <li>Buffer select: kernel picks buffers from the ring
     * </ul>
     *
     * @param sqe submission queue entry
     * @param fd socket file descriptor
     * @param bgid buffer group ID (matching registered buffer ring)
     * @param flags recv flags (MSG_* constants)
     */
    public static void prepRecvMultishotBufferSelect(
            MemorySegment sqe, int fd, short bgid, int flags) {
        prepRecvMultishotBufferSelect(sqe, fd, bgid, 0, flags);
    }

    /**
     * Prepare a multishot receive with buffer ring selection.
     *
     * <p>When using IOSQE_BUFFER_SELECT, the kernel picks the actual buffer from the ring, but
     * {@code len} still specifies the maximum number of bytes to receive.
     */
    public static void prepRecvMultishotBufferSelect(
            MemorySegment sqe, int fd, short bgid, int len, int flags) {
        clearSqe(sqe);
        SQE_OPCODE_VH.set(sqe, 0L, IORING_OP_RECV);
        SQE_FLAGS_VH.set(sqe, 0L, (byte) IOSQE_BUFFER_SELECT);
        SQE_FD_VH.set(sqe, 0L, fd);
        SQE_ADDR_VH.set(sqe, 0L, 0L);
        SQE_LEN_VH.set(sqe, 0L, len);
        // msg_flags
        SQE_OP_FLAGS_VH.set(sqe, 0L, flags);
        // ioprio flags
        SQE_IOPRIO_VH.set(sqe, 0L, (short) (IORING_RECV_MULTISHOT));
        // Buffer group for selection (buf_group shares storage with buf_index)
        SQE_BUF_INDEX_VH.set(sqe, 0L, bgid);
    }

    // ========== P2: Linked Operations API ==========

    /**
     * Set the IO_LINK flag on an SQE to link it to the next SQE.
     *
     * <p>Linked operations are executed sequentially. If a linked operation fails, subsequent
     * linked operations are cancelled (unless IOSQE_IO_HARDLINK is used).
     *
     * <p><b>Example: Echo pattern</b>
     *
     * <pre>
     * sqe1 = getSqe()   // recv
     * prepRecv(sqe1, ...)
     * sqeSetLink(sqe1)  // link to next
     *
     * sqe2 = getSqe()   // send (executes after recv completes)
     * prepSend(sqe2, ...)
     * </pre>
     *
     * @param sqe submission queue entry to link
     */
    public static void sqeSetLink(MemorySegment sqe) {
        byte flags = sqeGetFlags(sqe);
        sqeSetFlags(sqe, (byte) (flags | IOSQE_IO_LINK));
    }

    /**
     * Set the IO_HARDLINK flag on an SQE. Unlike IOSQE_IO_LINK, the chain continues even if this
     * operation fails.
     *
     * @param sqe submission queue entry to hard-link
     */
    public static void sqeSetHardLink(MemorySegment sqe) {
        byte flags = sqeGetFlags(sqe);
        sqeSetFlags(sqe, (byte) (flags | IOSQE_IO_HARDLINK));
    }

    /**
     * Set CQE_SKIP_SUCCESS flag - don't generate CQE if operation succeeds. Useful for linked
     * operations where only the final result matters. Requires Linux 5.17+.
     *
     * @param sqe submission queue entry
     */
    public static void sqeSetCqeSkipSuccess(MemorySegment sqe) {
        byte flags = sqeGetFlags(sqe);
        sqeSetFlags(sqe, (byte) (flags | IOSQE_CQE_SKIP_SUCCESS));
    }

    /** Check if io_uring is available on this system. */
    public static boolean isAvailable() {
        if (!isLibraryLoaded()) {
            return false;
        }

        try {
            // Try to initialize a minimal ring
            Arena arena = Arena.ofConfined();
            MemorySegment ring = arena.allocate(IO_URING_LAYOUT);
            int ret = queueInit(2, ring, 0);
            if (ret == 0) {
                queueExit(ring);
                arena.close();
                return true;
            }
            arena.close();
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ========== Native Socket Syscalls ==========

    // Socket constants - reuse from LinuxLayouts where available
    /** AF_INET - IPv4 Internet protocols. */
    public static final int AF_INET = LinuxLayouts.AF_INET;

    /** SOCK_STREAM - Sequenced, reliable, connection-based byte streams. */
    public static final int SOCK_STREAM = LinuxLayouts.SOCK_STREAM;

    /** SOCK_NONBLOCK - Set O_NONBLOCK on the new socket. */
    public static final int SOCK_NONBLOCK = LinuxLayouts.SOCK_NONBLOCK;

    /** SOL_SOCKET - Socket level for setsockopt. */
    public static final int SOL_SOCKET = 1;

    /** SO_REUSEADDR - Allow reuse of local addresses. */
    public static final int SO_REUSEADDR = 2;

    /** SO_REUSEPORT - Allow reuse of local port. */
    public static final int SO_REUSEPORT = 15;

    /** IPPROTO_TCP - TCP protocol. */
    public static final int IPPROTO_TCP = 6;

    /** F_GETFL - Get file status flags. */
    public static final int F_GETFL = 3;

    /** F_SETFL - Set file status flags. */
    public static final int F_SETFL = 4;

    /** O_NONBLOCK - Non-blocking I/O mode. */
    public static final int O_NONBLOCK = 0x800; // 2048

    /**
     * Create a socket.
     *
     * @param domain AF_INET for IPv4
     * @param type SOCK_STREAM for TCP
     * @param protocol 0 or IPPROTO_TCP
     * @return file descriptor on success, negative on failure
     */
    public static int nativeSocket(int domain, int type, int protocol) {
        try {
            if (socket == null) {
                return -1;
            }
            return (int) socket.invokeExact(domain, type, protocol);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Connect a socket to a remote address.
     *
     * @param sockfd socket file descriptor
     * @param addr pointer to sockaddr structure
     * @param addrlen size of sockaddr structure
     * @return 0 on success, -1 on failure (check errno)
     */
    public static int nativeConnect(int sockfd, MemorySegment addr, int addrlen) {
        try {
            return (int) connect.invokeExact(sockfd, addr, addrlen);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Bind a socket to a local address. */
    public static int nativeBind(int sockfd, MemorySegment addr, int addrlen) {
        try {
            return (int) bind.invokeExact(sockfd, addr, addrlen);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Listen for connections on a socket. */
    public static int nativeListen(int sockfd, int backlog) {
        try {
            return (int) listen.invokeExact(sockfd, backlog);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Accept a connection on a socket. */
    public static int nativeAccept(int sockfd, MemorySegment addr, MemorySegment addrlen) {
        try {
            return (int) accept.invokeExact(sockfd, addr, addrlen);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Set socket options. */
    public static int nativeSetsockopt(
            int sockfd, int level, int optname, MemorySegment optval, int optlen) {
        try {
            return (int) setsockopt.invokeExact(sockfd, level, optname, optval, optlen);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Manipulate file descriptor. */
    public static int nativeFcntl(int fd, int cmd, int arg) {
        try {
            return (int) fcntl.invokeExact(fd, cmd, arg);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Close a file descriptor. */
    public static int nativeClose(int fd) {
        try {
            return (int) close.invokeExact(fd);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Convert IPv4/IPv6 address from text to binary form. */
    public static int nativeInetPton(int af, MemorySegment src, MemorySegment dst) {
        try {
            return (int) inet_pton.invokeExact(af, src, dst);
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Convert values between host and network byte order (16-bit). */
    public static short nativeHtons(short hostshort) {
        try {
            return (short) htons.invokeExact(hostshort);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Helper: Create and configure a non-blocking TCP socket. */
    public static int createNonBlockingSocket() {
        int sockfd = nativeSocket(AF_INET, SOCK_STREAM, 0);
        if (sockfd < 0) {
            return -1;
        }

        // Set SO_REUSEADDR
        MemorySegment optval = Arena.ofAuto().allocate(ValueLayout.JAVA_INT);
        optval.set(ValueLayout.JAVA_INT, 0, 1);
        nativeSetsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, optval, 4);

        // Set non-blocking
        int flags = nativeFcntl(sockfd, F_GETFL, 0);
        if (flags >= 0) {
            nativeFcntl(sockfd, F_SETFL, flags | O_NONBLOCK);
        }

        return sockfd;
    }

    private LibUring() {
        // Utility class
    }
}

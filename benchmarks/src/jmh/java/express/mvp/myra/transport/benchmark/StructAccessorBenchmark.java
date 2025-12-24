package express.mvp.myra.transport.benchmark;

import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;
import static java.lang.foreign.MemoryLayout.paddingLayout;

import express.mvp.roray.ffm.utils.functions.StructAccessor;
import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Micro-benchmark comparing StructAccessor vs direct VarHandle for struct field access.
 *
 * <p>This benchmark measures the overhead (if any) of using StructAccessor abstraction
 * versus direct VarHandle operations for reading/writing struct fields.
 *
 * <p>Uses io_uring_sqe layout (64 bytes) as test case since this is the hot path structure.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class StructAccessorBenchmark {

    /**
     * io_uring_sqe layout (64 bytes) - same as in LibUring.
     */
    public static final StructLayout SQE_LAYOUT =
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
                    JAVA_SHORT.withName("buf_index"),
                    JAVA_SHORT.withName("buf_group"),
                    JAVA_INT.withName("personality"),
                    JAVA_INT.withName("splice_fd_in"),
                    paddingLayout(4),
                    JAVA_LONG.withName("addr3"))
                    .withName("io_uring_sqe");

    // Direct VarHandles for each field
    private static final VarHandle OPCODE_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("opcode"));
    private static final VarHandle FLAGS_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    private static final VarHandle FD_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("fd"));
    private static final VarHandle ADDR_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("addr"));
    private static final VarHandle LEN_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("len"));
    private static final VarHandle OP_FLAGS_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("op_flags"));
    private static final VarHandle USER_DATA_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("user_data"));
    private static final VarHandle BUF_INDEX_VH = SQE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("buf_index"));

    // StructAccessor for the same layout
    private static final StructAccessor SQE_ACCESSOR = StructAccessor.of(SQE_LAYOUT);

    // VarHandles extracted from StructAccessor (recommended hot-path pattern)
    private static final VarHandle SA_OPCODE_VH = SQE_ACCESSOR.varHandle("opcode");
    private static final VarHandle SA_FD_VH = SQE_ACCESSOR.varHandle("fd");
    private static final VarHandle SA_ADDR_VH = SQE_ACCESSOR.varHandle("addr");
    private static final VarHandle SA_LEN_VH = SQE_ACCESSOR.varHandle("len");
    private static final VarHandle SA_OP_FLAGS_VH = SQE_ACCESSOR.varHandle("op_flags");
    private static final VarHandle SA_USER_DATA_VH = SQE_ACCESSOR.varHandle("user_data");

    // Pre-allocated memory
    private Arena arena;
    private MemorySegment sqe;

    // Test values
    private static final byte TEST_OPCODE = 26; // IORING_OP_SEND
    private static final int TEST_FD = 42;
    private static final long TEST_ADDR = 0x7fff_1234_5678L;
    private static final int TEST_LEN = 4096;
    private static final long TEST_USER_DATA = 0xDEAD_BEEF_CAFE_BABEL;

    @Setup(Level.Trial)
    public void setup() {
        arena = Arena.ofConfined();
        sqe = arena.allocate(SQE_LAYOUT);
        sqe.fill((byte) 0);
    }

    // ========== Write benchmarks ==========

    @Benchmark
    public void directVarHandle_prepSend() {
        // Simulate prepSend operation using direct VarHandles
        sqe.fill((byte) 0); // clearSqe equivalent
        OPCODE_VH.set(sqe, 0L, TEST_OPCODE);
        FD_VH.set(sqe, 0L, TEST_FD);
        ADDR_VH.set(sqe, 0L, TEST_ADDR);
        LEN_VH.set(sqe, 0L, TEST_LEN);
        OP_FLAGS_VH.set(sqe, 0L, 0);
        USER_DATA_VH.set(sqe, 0L, TEST_USER_DATA);
    }

    @Benchmark
    public void structAccessor_prepSend() {
        // Simulate prepSend operation using StructAccessor
        sqe.fill((byte) 0); // clearSqe equivalent
        SQE_ACCESSOR.setByte(sqe, "opcode", TEST_OPCODE);
        SQE_ACCESSOR.setInt(sqe, "fd", TEST_FD);
        SQE_ACCESSOR.setLong(sqe, "addr", TEST_ADDR);
        SQE_ACCESSOR.setInt(sqe, "len", TEST_LEN);
        SQE_ACCESSOR.setInt(sqe, "op_flags", 0);
        SQE_ACCESSOR.setLong(sqe, "user_data", TEST_USER_DATA);
    }

    // ========== Read benchmarks ==========

    @Benchmark
    public long directVarHandle_readFields() {
        // Read multiple fields and combine (simulate CQE processing)
        byte opcode = (byte) OPCODE_VH.get(sqe, 0L);
        int fd = (int) FD_VH.get(sqe, 0L);
        long userData = (long) USER_DATA_VH.get(sqe, 0L);
        return opcode + fd + userData;
    }

    @Benchmark
    public long structAccessor_readFields() {
        // Read multiple fields and combine (simulate CQE processing)
        byte opcode = SQE_ACCESSOR.getByte(sqe, "opcode");
        int fd = SQE_ACCESSOR.getInt(sqe, "fd");
        long userData = SQE_ACCESSOR.getLong(sqe, "user_data");
        return opcode + fd + userData;
    }

    // ========== Single field access benchmarks ==========

    @Benchmark
    public void directVarHandle_setSingleField() {
        USER_DATA_VH.set(sqe, 0L, TEST_USER_DATA);
    }

    @Benchmark
    public void structAccessor_setSingleField() {
        SQE_ACCESSOR.setLong(sqe, "user_data", TEST_USER_DATA);
    }

    @Benchmark
    public long directVarHandle_getSingleField() {
        return (long) USER_DATA_VH.get(sqe, 0L);
    }

    @Benchmark
    public long structAccessor_getSingleField() {
        return SQE_ACCESSOR.getLong(sqe, "user_data");
    }

    // ========== StructAccessor with extracted VarHandles (recommended hot-path pattern) ==========

    @Benchmark
    public void structAccessorVarHandle_prepSend() {
        // Simulate prepSend using VarHandles extracted from StructAccessor
        sqe.fill((byte) 0);
        SA_OPCODE_VH.set(sqe, 0L, TEST_OPCODE);
        SA_FD_VH.set(sqe, 0L, TEST_FD);
        SA_ADDR_VH.set(sqe, 0L, TEST_ADDR);
        SA_LEN_VH.set(sqe, 0L, TEST_LEN);
        SA_OP_FLAGS_VH.set(sqe, 0L, 0);
        SA_USER_DATA_VH.set(sqe, 0L, TEST_USER_DATA);
    }

    @Benchmark
    public long structAccessorVarHandle_readFields() {
        byte opcode = (byte) SA_OPCODE_VH.get(sqe, 0L);
        int fd = (int) SA_FD_VH.get(sqe, 0L);
        long userData = (long) SA_USER_DATA_VH.get(sqe, 0L);
        return opcode + fd + userData;
    }

    @Benchmark
    public void structAccessorVarHandle_setSingleField() {
        SA_USER_DATA_VH.set(sqe, 0L, TEST_USER_DATA);
    }

    @Benchmark
    public long structAccessorVarHandle_getSingleField() {
        return (long) SA_USER_DATA_VH.get(sqe, 0L);
    }
}

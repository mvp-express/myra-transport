package express.mvp.myra.transport.benchmark;

import express.mvp.roray.utils.functions.DowncallFactory;
import express.mvp.roray.utils.functions.FunctionDescriptorBuilder;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
 * Micro-benchmark comparing DowncallFactory vs direct LINKER.downcallHandle().
 *
 * <p>This benchmark measures the overhead (if any) of using DowncallFactory abstraction
 * versus direct Linker API calls for native function invocation.
 *
 * <p>Tests three scenarios:
 * <ul>
 *   <li>Direct LINKER.downcallHandle() - baseline
 *   <li>DowncallFactory - abstraction layer
 *   <li>DowncallFactory with critical(false) - for hot path optimization
 * </ul>
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
public class DowncallFactoryBenchmark {

    // Native linker and symbol lookup
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

    // Function descriptor for getpid() - simplest syscall with no arguments
    private static final FunctionDescriptor GETPID_DESC = FunctionDescriptor.of(ValueLayout.JAVA_INT);

    // Function descriptor for clock_gettime - more complex with pointer argument
    private static final FunctionDescriptor CLOCK_GETTIME_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS);

    // MethodHandles created via direct LINKER API
    private MethodHandle directGetpid;
    private MethodHandle directClockGettime;

    // MethodHandles created via DowncallFactory
    private MethodHandle factoryGetpid;
    private MethodHandle factoryClockGettime;

    // MethodHandles created via DowncallFactory with critical(false)
    private MethodHandle factoryCriticalGetpid;
    private MethodHandle factoryCriticalClockGettime;

    // Reusable memory for clock_gettime benchmark
    private Arena arena;
    private MemorySegment timespec;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // Direct LINKER approach
        directGetpid = LINKER.downcallHandle(
                LOOKUP.find("getpid").orElseThrow(),
                GETPID_DESC);

        directClockGettime = LINKER.downcallHandle(
                LOOKUP.find("clock_gettime").orElseThrow(),
                CLOCK_GETTIME_DESC);

        // DowncallFactory approach
        DowncallFactory factory = DowncallFactory.forNativeLinker();

        factoryGetpid = factory.downcall("getpid", GETPID_DESC);

        factoryClockGettime = factory.downcall(
                "clock_gettime",
                FunctionDescriptorBuilder.returnsInt()
                        .args(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
                        .build());

        // DowncallFactory with critical(false) option
        factoryCriticalGetpid = factory.downcall(
                "getpid",
                GETPID_DESC,
                Linker.Option.critical(false));

        factoryCriticalClockGettime = factory.downcall(
                "clock_gettime",
                FunctionDescriptorBuilder.returnsInt()
                        .args(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
                        .build(),
                Linker.Option.critical(false));

        // Allocate memory for clock_gettime
        arena = Arena.ofConfined();
        // struct timespec { time_t tv_sec; long tv_nsec; } = 16 bytes
        timespec = arena.allocate(16, 8);
    }

    // ========== getpid() benchmarks - simplest syscall ==========

    @Benchmark
    public int directLinker_getpid() throws Throwable {
        return (int) directGetpid.invokeExact();
    }

    @Benchmark
    public int downcallFactory_getpid() throws Throwable {
        return (int) factoryGetpid.invokeExact();
    }

    @Benchmark
    public int downcallFactory_critical_getpid() throws Throwable {
        return (int) factoryCriticalGetpid.invokeExact();
    }

    // ========== clock_gettime() benchmarks - with pointer argument ==========

    private static final int CLOCK_MONOTONIC = 1;

    @Benchmark
    public int directLinker_clockGettime() throws Throwable {
        return (int) directClockGettime.invokeExact(CLOCK_MONOTONIC, timespec);
    }

    @Benchmark
    public int downcallFactory_clockGettime() throws Throwable {
        return (int) factoryClockGettime.invokeExact(CLOCK_MONOTONIC, timespec);
    }

    @Benchmark
    public int downcallFactory_critical_clockGettime() throws Throwable {
        return (int) factoryCriticalClockGettime.invokeExact(CLOCK_MONOTONIC, timespec);
    }
}

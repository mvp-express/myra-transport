# io_uring P2/P3 Optimization Summary

## Overview
This document summarizes the Stage 3 (P2 priority) and Stage 4 (P3 priority) io_uring optimizations implemented in the myra-transport library.

## Implemented Features

### Stage 3: P2 Priority

#### Phase 4: Buffer Ring (IORING_REGISTER_PBUF_RING)
**Purpose:** Kernel-managed buffer selection for efficient multishot receive

**LibUring.java additions:**
- `IORING_REGISTER_PBUF_RING` (22), `IORING_UNREGISTER_PBUF_RING` (23)
- `IOU_PBUF_RING_MMAP` flag
- `IO_URING_BUF_LAYOUT` - Single buffer entry structure (16 bytes)
- `IO_URING_BUF_RING_LAYOUT` - Buffer ring header structure
- `IO_URING_BUF_REG_LAYOUT` - Buffer ring registration structure
- `isBufferRingSupported()` - Check if kernel supports buffer rings
- `registerBufferRing()` / `unregisterBufferRing()` - Register/unregister buffer ring
- `bufferRingInit()` - Initialize buffer ring header
- `bufferRingAdd()` - Add buffer to ring
- `bufferRingAdvance()` - Advance tail to make buffers visible
- `cqeGetBufferId()` - Extract buffer ID from CQE
- `cqeHasBuffer()` - Check if CQE has IORING_CQE_F_BUFFER flag
- `prepRecvMultishotBufferSelect()` - Multishot recv with buffer ring

**IoUringBackend.java additions:**
- `initBufferRing(nentries, bufSize, bgid)` - Initialize buffer ring with custom params
- `initBufferRing()` - Initialize with defaults (256 entries, 8KB each)
- `isBufferRingEnabled()` - Check if buffer ring is active
- `getBufferGroupId()` - Get buffer group ID
- `getBufferRingBuffer(bufferId)` - Get buffer by ID from ring
- `recycleBufferRingBuffer(bufferId)` - Return buffer to ring
- `submitMultishotRecvWithBufferRing(token)` - Submit multishot recv with buffer select

#### Phase 6: Linked Operations (IOSQE_IO_LINK)
**Purpose:** Chain SQEs for efficient request-response patterns

**LibUring.java additions:**
- `IOSQE_IO_HARDLINK` (1 << 3) - Hard link (chain continues on failure)
- `IOSQE_CQE_SKIP_SUCCESS` (1 << 6) - Skip CQE on success
- `sqeSetLink(sqe)` - Set IOSQE_IO_LINK flag
- `sqeSetHardLink(sqe)` - Set IOSQE_IO_HARDLINK flag
- `sqeSetCqeSkipSuccess(sqe)` - Set CQE_SKIP_SUCCESS flag

**IoUringBackend.java additions:**
- `submitLinkedEcho(recvBuffer, recvLen, recvToken, sendToken)` - Linked recv→send
- `submitLinkedEchoSkipRecvCqe(recvBuffer, recvLen, sendToken)` - Echo with skip success
- `submitLinkedRequestResponse(sendBuffer, sendLen, recvBuffer, recvLen, sendToken, recvToken)` - Request-response pattern

### Stage 4: P3 Priority

#### Phase 7: Fixed File Optimization
**Purpose:** Ensure consistent use of fixed file descriptors

**IoUringBackend.java additions:**
- `isUsingFixedFile()` - Check if fixed file optimization is active
- `getFixedFileIndex()` - Get fixed file index (or -1)
- All batch operations use fixed files when available

#### Phase 8: Batch Receive
**Purpose:** Submit multiple recv operations in single batch for throughput

**IoUringBackend.java additions:**
- `submitBatchRecv(buffers[], lengths[], tokens[], count)` - Batch recv with buffers
- `submitBatchSend(buffers[], lengths[], tokens[], count)` - Batch send with buffers
- `submitBatchRecvRegistered(bufferIndices[], lengths[], tokens[], count)` - Batch recv with registered buffers
- `forceSubmit()` - Force io_uring_submit syscall

## Benchmark Results

### Configuration
- **Warmup:** 1 iteration, 30 seconds
- **Measurement:** 3 iterations, 30 seconds each
- **Platform:** Linux 6.14.0 (Oracle kernel), ARM64 (AWS Graviton)
- **JVM:** JDK 25.0.1 (Temurin)

### Results After P2+P3 Implementation

| Metric | MYRA | MYRA_SQPOLL | Improvement |
|--------|------|-------------|-------------|
| Mean | 31.89 μs | 28.28 μs | 11.3% |
| p50 (median) | 28.99 μs | 23.04 μs | **20.5%** |
| p90 | 31.78 μs | 24.90 μs | 21.7% |
| p99 | 44.25 μs | 50.75 μs | -14.7% |
| p0 (min) | 26.46 μs | 20.22 μs | 23.6% |

### Comparison with Previous Baseline (P0+P1)

| Metric | Previous p50 | Current p50 | Change |
|--------|-------------|-------------|--------|
| MYRA | 28.48 μs | 28.99 μs | +1.8% (within margin) |
| MYRA_SQPOLL | 22.94 μs | 23.04 μs | +0.4% (stable) |

**Conclusion:** Performance is stable with P2+P3 features added. The new API provides building blocks for higher-level optimizations.

## Test Coverage

### LibUringTest.java (New Tests)
- Buffer ring constants and layouts (6 tests)
- `bufferRingInit()`, `bufferRingAdd()`, `bufferRingAdvance()` (4 tests)
- `cqeHasBuffer()`, `cqeGetBufferId()` (3 tests)
- Linked operations flags and methods (7 tests)
- `prepRecvMultishotBufferSelect()` verification (1 test)

### IoUringBackendTest.java (New Tests)
- Buffer ring initialization tests (6 tests)
- Linked operations tests (3 tests)
- Batch operations tests (5 tests)
- Fixed file tests (3 tests)

## Usage Examples

### Buffer Ring with Multishot Receive
```java
IoUringBackend backend = new IoUringBackend();
backend.initialize(config);

// Initialize buffer ring (256 buffers of 8KB each)
if (backend.initBufferRing()) {
    // Submit multishot receive
    backend.submitMultishotRecvWithBufferRing(RECV_TOKEN);
    backend.forceSubmit();
    
    // Process completions
    backend.poll((token, res, flags) -> {
        if (token == RECV_TOKEN && res > 0) {
            int bufferId = LibUring.cqeGetBufferId(flags);
            MemorySegment buffer = backend.getBufferRingBuffer(bufferId);
            // Process data...
            backend.recycleBufferRingBuffer(bufferId);
        }
    });
}
```

### Linked Request-Response
```java
// RPC client pattern: send request, then receive response
try (Arena arena = Arena.ofConfined()) {
    MemorySegment request = arena.allocate(1024);
    MemorySegment response = arena.allocate(4096);
    
    // Fill request...
    
    backend.submitLinkedRequestResponse(
        request, requestLen,
        response, 4096,
        SEND_TOKEN, RECV_TOKEN
    );
    backend.forceSubmit();
}
```

### Batch Receive
```java
MemorySegment[] buffers = new MemorySegment[8];
int[] lengths = new int[8];
long[] tokens = new long[8];

for (int i = 0; i < 8; i++) {
    buffers[i] = arena.allocate(4096);
    lengths[i] = 4096;
    tokens[i] = 100L + i;
}

int submitted = backend.submitBatchRecv(buffers, lengths, tokens, 8);
backend.forceSubmit();
```

## Notes

1. **Buffer Ring Availability:** Buffer rings require Linux 5.19+ and liburing 2.3+. The implementation gracefully falls back if not supported (errno=22).

2. **SQPOLL Compatibility:** COOP_TASKRUN flag may not be available on all kernels. The implementation automatically falls back to basic SQPOLL if needed.

3. **Fixed Files:** When available, fixed file descriptors are automatically used for all operations, eliminating fd lookup overhead.

4. **Performance Characteristics:** 
   - Buffer rings shine for multishot receive (persistent connections)
   - Linked operations are ideal for RPC request-response patterns
   - Batch operations maximize throughput for high-volume scenarios


package express.mvp.myra.transport.buffer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BufferPoolMetrics} record.
 */
class BufferPoolMetricsTest {

    @Test
    void successRate_withValidData_returnsCorrectRatio() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 75, 25, 1000, 5000, 10, 20);
        assertEquals(0.75, metrics.successRate(), 0.0001);
    }

    @Test
    void successRate_withZeroTotal_returnsZero() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(0, 0, 0, 0, 0, 10, 20);
        assertEquals(0.0, metrics.successRate());
    }

    @Test
    void failureRate_withValidData_returnsCorrectRatio() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 75, 25, 1000, 5000, 10, 20);
        assertEquals(0.25, metrics.failureRate(), 0.0001);
    }

    @Test
    void failureRate_withZeroTotal_returnsZero() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(0, 0, 0, 0, 0, 10, 20);
        assertEquals(0.0, metrics.failureRate());
    }

    @Test
    void utilization_withFullyAvailable_returnsZero() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 100, 0, 0, 0, 20, 20);
        assertEquals(0.0, metrics.utilization(), 0.0001);
    }

    @Test
    void utilization_withAllInUse_returnsOne() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 100, 0, 0, 0, 0, 20);
        assertEquals(1.0, metrics.utilization(), 0.0001);
    }

    @Test
    void utilization_withPartialUse_returnsCorrectRatio() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 100, 0, 0, 0, 5, 20);
        assertEquals(0.75, metrics.utilization(), 0.0001);
    }

    @Test
    void utilization_withZeroPoolSize_returnsZero() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, metrics.utilization());
    }

    @Test
    void avgWaitTimeMillis_convertsCorrectly() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 100, 0, 5_000_000, 0, 10, 20);
        assertEquals(5.0, metrics.avgWaitTimeMillis(), 0.0001);
    }

    @Test
    void maxWaitTimeMillis_convertsCorrectly() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 100, 0, 0, 25_000_000, 10, 20);
        assertEquals(25.0, metrics.maxWaitTimeMillis(), 0.0001);
    }

    @Test
    void toString_containsAllFields() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 80, 20, 5_000_000, 50_000_000, 5, 10);
        String str = metrics.toString();

        assertTrue(str.contains("total=100"));
        assertTrue(str.contains("success=80"));
        assertTrue(str.contains("failed=20"));
        assertTrue(str.contains("avgWait=5.000ms"));
        assertTrue(str.contains("maxWait=50.000ms"));
        assertTrue(str.contains("available=5/10"));
        assertTrue(str.contains("utilization=50.0%"));
    }

    @Test
    void recordEquality_worksCorrectly() {
        BufferPoolMetrics m1 = new BufferPoolMetrics(100, 80, 20, 1000, 5000, 10, 20);
        BufferPoolMetrics m2 = new BufferPoolMetrics(100, 80, 20, 1000, 5000, 10, 20);
        BufferPoolMetrics m3 = new BufferPoolMetrics(100, 80, 20, 1000, 5001, 10, 20);

        assertEquals(m1, m2);
        assertNotEquals(m1, m3);
        assertEquals(m1.hashCode(), m2.hashCode());
    }

    @Test
    void componentAccessors_returnCorrectValues() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(1, 2, 3, 4, 5, 6, 7);

        assertEquals(1, metrics.totalAcquisitions());
        assertEquals(2, metrics.successfulAcquisitions());
        assertEquals(3, metrics.failedAcquisitions());
        assertEquals(4, metrics.avgWaitTimeNanos());
        assertEquals(5, metrics.maxWaitTimeNanos());
        assertEquals(6, metrics.currentAvailable());
        assertEquals(7, metrics.poolSize());
    }

    @Test
    void ratesAddUpToOne() {
        BufferPoolMetrics metrics = new BufferPoolMetrics(100, 60, 40, 0, 0, 10, 20);
        assertEquals(1.0, metrics.successRate() + metrics.failureRate(), 0.0001);
    }
}

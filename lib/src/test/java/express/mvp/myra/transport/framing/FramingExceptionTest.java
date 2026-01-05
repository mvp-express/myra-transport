package express.mvp.myra.transport.framing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

/** Tests for {@link FramingException}. */
class FramingExceptionTest {

    @Test
    @DisplayName("Exception with message only")
    void exceptionWithMessage() {
        String message = "Test framing error";
        FramingException ex = new FramingException(message);

        assertEquals(message, ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("Exception with message and cause")
    void exceptionWithMessageAndCause() {
        String message = "Test framing error";
        RuntimeException cause = new RuntimeException("Root cause");
        FramingException ex = new FramingException(message, cause);

        assertEquals(message, ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    @Test
    @DisplayName("Exception with cause only")
    void exceptionWithCauseOnly() {
        RuntimeException cause = new RuntimeException("Root cause");
        FramingException ex = new FramingException(cause);

        assertEquals(cause, ex.getCause());
        assertTrue(ex.getMessage().contains("Root cause"));
    }

    @Test
    @DisplayName("Exception is a RuntimeException")
    void isRuntimeException() {
        FramingException ex = new FramingException("test");

        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    @DisplayName("Exception can be thrown and caught")
    void canBeThrown() {
        assertThrows(
                FramingException.class,
                () -> {
                    throw new FramingException("Test error");
                });
    }

    @Test
    @DisplayName("Exception preserves stack trace")
    void preservesStackTrace() {
        FramingException ex = new FramingException("test");
        StackTraceElement[] trace = ex.getStackTrace();

        assertNotNull(trace);
        assertTrue(trace.length > 0);
        assertTrue(
                trace[0].getMethodName().contains("preservesStackTrace")
                        || trace[0].getClassName().contains("FramingExceptionTest"));
    }
}

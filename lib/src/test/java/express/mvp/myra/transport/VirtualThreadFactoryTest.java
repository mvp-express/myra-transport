package express.mvp.myra.transport;

import static org.junit.jupiter.api.Assertions.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

/** Tests for {@link VirtualThreadFactory}. */
@SuppressFBWarnings(
        value = {"THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION"},
        justification = "SpotBugs rules are intentionally relaxed for test scaffolding.")
class VirtualThreadFactoryTest {

    @Test
    @DisplayName("Create factory with name prefix")
    void createWithNamePrefix() {
        VirtualThreadFactory factory = new VirtualThreadFactory("test-worker");
        assertEquals("test-worker", factory.getNamePrefix());
        assertTrue(factory.isDaemon());
        assertEquals(0, factory.getThreadCount());
    }

    @Test
    @DisplayName("Create factory with daemon flag")
    void createWithDaemonFlag() {
        VirtualThreadFactory factoryDaemon = new VirtualThreadFactory("test", true);
        assertTrue(factoryDaemon.isDaemon());

        VirtualThreadFactory factoryNonDaemon = new VirtualThreadFactory("test", false);
        assertFalse(factoryNonDaemon.isDaemon());
    }

    @Test
    @DisplayName("newThread creates virtual thread")
    void newThreadCreatesVirtualThread() throws Exception {
        VirtualThreadFactory factory = new VirtualThreadFactory("test");
        CountDownLatch latch = new CountDownLatch(1);

        Thread thread = factory.newThread(latch::countDown);
        assertNotNull(thread);
        assertTrue(thread.isVirtual());
        assertEquals("test-1", thread.getName());
        assertEquals(1, factory.getThreadCount());

        // Thread should not be started
        assertFalse(thread.isAlive());

        // Start and wait
        thread.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Thread names increment")
    void threadNamesIncrement() {
        VirtualThreadFactory factory = new VirtualThreadFactory("worker");

        Thread t1 = factory.newThread(() -> {});
        Thread t2 = factory.newThread(() -> {});
        Thread t3 = factory.newThread(() -> {});

        assertEquals("worker-1", t1.getName());
        assertEquals("worker-2", t2.getName());
        assertEquals("worker-3", t3.getName());
        assertEquals(3, factory.getThreadCount());
    }

    @Test
    @DisplayName("Thread count is thread-safe")
    void threadCountThreadSafe() throws Exception {
        VirtualThreadFactory factory = new VirtualThreadFactory("concurrent");
        int threadCount = 1000;
        CountDownLatch latch = new CountDownLatch(threadCount);

        // Create threads from multiple threads concurrently
        Thread[] creators = new Thread[10];
        for (int i = 0; i < creators.length; i++) {
            creators[i] =
                    Thread.ofPlatform()
                            .unstarted(
                                    () -> {
                                        for (int j = 0; j < threadCount / creators.length; j++) {
                                            factory.newThread(() -> {});
                                            latch.countDown();
                                        }
                                    });
        }

        for (Thread creator : creators) {
            creator.start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount, factory.getThreadCount());
    }

    @Test
    @DisplayName("toString provides useful information")
    void toStringFormat() {
        VirtualThreadFactory factory = new VirtualThreadFactory("test");
        factory.newThread(() -> {});
        factory.newThread(() -> {});

        String str = factory.toString();
        assertTrue(str.contains("VirtualThreadFactory"));
        assertTrue(str.contains("prefix=test"));
        assertTrue(str.contains("created=2"));
    }

    @Test
    @DisplayName("Created threads execute tasks correctly")
    void threadsExecuteTasks() throws Exception {
        VirtualThreadFactory factory = new VirtualThreadFactory("task-test");
        int taskCount = 100;
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            Thread thread = factory.newThread(latch::countDown);
            thread.start();
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(taskCount, factory.getThreadCount());
    }
}

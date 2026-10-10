package dimblend.asyncsave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncSaveQueueTest {
    @Test
    void runsSubmittedWrites() {
        AtomicInteger runs = new AtomicInteger();
        AsyncSaveQueue.submit(Path.of("a.dat"), runs::incrementAndGet);
        AsyncSaveQueue.drain();
        assertEquals(1, runs.get());
    }

    @Test
    void coalescesToLatestWritePerTarget() {
        List<String> writes = new ArrayList<>();
        // 用门闩挡住写线程，保证两份快照投递时第一份还排在队列里
        CountDownLatch gate = new CountDownLatch(1);
        AsyncSaveQueue.submit(Path.of("gate"), () -> gate.await());
        AsyncSaveQueue.submit(Path.of("b.dat"), () -> writes.add("old"));
        AsyncSaveQueue.submit(Path.of("b.dat"), () -> writes.add("new"));
        gate.countDown();
        AsyncSaveQueue.drain();
        assertEquals(List.of("new"), writes);
    }

    @Test
    void doesNotCoalesceAcrossTargets() {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        AsyncSaveQueue.submit(Path.of("gate2"), () -> gate.await());
        AsyncSaveQueue.submit(Path.of("c1.dat"), runs::incrementAndGet);
        AsyncSaveQueue.submit(Path.of("c2.dat"), runs::incrementAndGet);
        gate.countDown();
        AsyncSaveQueue.drain();
        assertEquals(2, runs.get());
    }

    @Test
    void keepsWritingAfterAFailure() {
        AtomicInteger runs = new AtomicInteger();
        AsyncSaveQueue.submit(Path.of("d.dat"), () -> {
            throw new IllegalStateException("boom");
        });
        AsyncSaveQueue.submit(Path.of("e.dat"), runs::incrementAndGet);
        AsyncSaveQueue.drain();
        assertEquals(1, runs.get());
    }

    @Test
    void drainWaitsForInFlightWrites() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        AsyncSaveQueue.submit(Path.of("f.dat"), () -> {
            started.countDown();
            finish.await();
            done.incrementAndGet();
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Thread drainer = new Thread(() -> {
            AsyncSaveQueue.drain();
            done.incrementAndGet();
        });
        drainer.start();
        Thread.sleep(100);
        assertEquals(0, done.get());
        finish.countDown();
        drainer.join(10000);
        assertEquals(2, done.get());
    }
}

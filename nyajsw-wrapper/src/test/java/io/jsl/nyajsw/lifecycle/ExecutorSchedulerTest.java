package io.jsl.nyajsw.lifecycle;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.Test;

/** The real scheduler, with short real delays; bounded waits only. */
public class ExecutorSchedulerTest {

    @Test(timeOut = 60_000)
    public void aTaskRunsAfterItsDelayAndACancelledOneDoesNot() throws InterruptedException {
        try (ExecutorScheduler scheduler = new ExecutorScheduler()) {
            CountDownLatch ran = new CountDownLatch(1);
            AtomicBoolean cancelledRan = new AtomicBoolean();
            Duration before = scheduler.now();

            scheduler.schedule(Duration.ofMillis(200), () -> cancelledRan.set(true)).cancel();
            scheduler.schedule(Duration.ofMillis(50), ran::countDown);

            assertTrue(ran.await(30, TimeUnit.SECONDS));
            assertTrue(scheduler.now().minus(before).compareTo(Duration.ofMillis(50)) >= 0);
            // The cancelled task was due before this one; give it its time to be sure it stays cancelled.
            CountDownLatch later = new CountDownLatch(1);
            scheduler.schedule(Duration.ofMillis(300), later::countDown);
            assertTrue(later.await(30, TimeUnit.SECONDS));
            assertFalse(cancelledRan.get());
        }
    }

    @Test
    public void anEnormousDelayIsAcceptedAsNever() {
        try (ExecutorScheduler scheduler = new ExecutorScheduler()) {
            scheduler.schedule(Duration.ofSeconds(Long.MAX_VALUE / 2), () -> { }).cancel();
        }
    }
}

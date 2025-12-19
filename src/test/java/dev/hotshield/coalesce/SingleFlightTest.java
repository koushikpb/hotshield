package dev.hotshield.coalesce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// Fails until you write SingleFlight (milestone 3).
@Timeout(10)
class SingleFlightTest {

    static final int CALLERS = 1_000;

    SingleFlight<String, String> flight = new SingleFlight<>();

    @Test
    void concurrentCallsForOneKeyRunTheLoaderOnce() throws Exception {
        var loads = new AtomicInteger();
        var allStarted = new CountDownLatch(CALLERS);

        List<Future<String>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLERS; i++) {
                results.add(executor.submit(() -> {
                    allStarted.countDown();
                    return flight.run("hot", () -> {
                        loads.incrementAndGet();
                        // Hold the leader until every caller has at least reached run(). The short sleep covers
                        // the gap between countDown() and the follower joining; without some hold, the leader
                        // can finish first and late callers would correctly start a second load.
                        await(allStarted);
                        sleep(Duration.ofMillis(200));
                        return "value";
                    });
                }));
            }
        }

        assertThat(loads).hasValue(1);
        for (var result : results) {
            assertThat(result.get()).isEqualTo("value");
        }
    }

    @Test
    void leaderFailureReachesEveryWaiterAndIsNotRemembered() throws Exception {
        var leaderRunning = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> leader = executor.submit(() -> flight.run("k", () -> {
                leaderRunning.countDown();
                await(release);
                throw new IllegalStateException("db down");
            }));
            await(leaderRunning);
            Future<String> follower = executor.submit(() -> flight.run("k", () -> "follower should not load"));
            sleep(Duration.ofMillis(100));
            release.countDown();

            assertCause(leader, IllegalStateException.class);
            assertCause(follower, IllegalStateException.class);
        }

        assertThat(flight.run("k", () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    void differentKeysDoNotWaitForEachOther() throws Exception {
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> slow = executor.submit(() -> flight.run("a", () -> {
                await(release);
                return "a";
            }));

            Future<String> fast = executor.submit(() -> flight.run("b", () -> "b"));

            assertThat(fast.get(2, TimeUnit.SECONDS)).isEqualTo("b");
            release.countDown();
            assertThat(slow.get()).isEqualTo("a");
        }
    }

    @Test
    void sequentialCallsEachRunTheLoader() {
        var loads = new AtomicInteger();

        flight.run("k", () -> "load " + loads.incrementAndGet());
        flight.run("k", () -> "load " + loads.incrementAndGet());

        assertThat(loads).hasValue(2);
    }

    static void assertCause(Future<?> future, Class<? extends Throwable> type) {
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(type);
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

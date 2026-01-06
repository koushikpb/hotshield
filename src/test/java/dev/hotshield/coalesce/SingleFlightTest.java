package dev.hotshield.coalesce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// Fails until you write SingleFlight (milestone 3).
// An implementation that runs the loader under synchronized or inside ConcurrentHashMap.computeIfAbsent can
// pin every carrier thread and hang the JVM; the Gradle test timeout turns that into a failure. That hang is
// the virtual-thread pinning problem the plan warns about, not a test bug.
@Timeout(10)
class SingleFlightTest {

    static final int CALLERS = 1_000;

    SingleFlight<String, String> flight = new SingleFlight<>();

    @Test
    void concurrentCallsForOneKeyRunTheLoaderOnce() throws Exception {
        var loads = new AtomicInteger();
        var allStarted = new CountDownLatch(CALLERS);
        Set<Thread> callers = ConcurrentHashMap.newKeySet();

        List<Future<String>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLERS; i++) {
                results.add(executor.submit(() -> {
                    callers.add(Thread.currentThread());
                    allStarted.countDown();
                    return flight.run("hot", () -> {
                        loads.incrementAndGet();
                        // Hold the leader until every other caller is parked. After countDown() the only place
                        // a caller can park is inside run(), so this proves they all joined the flight without
                        // relying on a sleep being long enough.
                        await(allStarted);
                        awaitParked(callers, Thread.currentThread());
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

    // Followers must get the leader's own exception, not a new one and not a wrapper, so coalesce mode fails
    // with the same exception types as direct mode.
    @Test
    void leaderFailureReachesEveryWaiterAndIsNotRemembered() throws Exception {
        var boom = new IllegalStateException("db down");
        var leaderRunning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var followerThread = new AtomicReference<Thread>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> leader = executor.submit(() -> flight.run("k", () -> {
                leaderRunning.countDown();
                await(release);
                throw boom;
            }));
            await(leaderRunning);
            Future<String> follower = executor.submit(() -> {
                followerThread.set(Thread.currentThread());
                return flight.run("k", () -> "follower should not load");
            });
            awaitParked(followerThread);
            release.countDown();

            assertSameCause(leader, boom);
            assertSameCause(follower, boom);
        }

        assertThat(flight.run("k", () -> "fresh")).isEqualTo("fresh");
    }

    @Test
    void interruptedLeaderFailsFollowersAndIsNotRemembered() throws Exception {
        var leaderRunning = new CountDownLatch(1);
        var leaderThread = new AtomicReference<Thread>();
        var followerThread = new AtomicReference<Thread>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                leaderThread.set(Thread.currentThread());
                return flight.run("k", () -> {
                    leaderRunning.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("leader cancelled", e);
                    }
                    return "never";
                });
            });
            await(leaderRunning);
            Future<String> follower = executor.submit(() -> {
                followerThread.set(Thread.currentThread());
                return flight.run("k", () -> "follower should not load");
            });
            awaitParked(followerThread);

            leaderThread.get().interrupt();

            assertThatThrownBy(() -> follower.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        }

        assertThat(flight.run("k", () -> "fresh")).isEqualTo("fresh");
    }

    // One impatient caller giving up must not take the leader or the other waiters down with it.
    @Test
    void interruptedFollowerDoesNotAffectOthers() throws Exception {
        var leaderRunning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicReference<Thread>();
        var second = new AtomicReference<Thread>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> leader = executor.submit(() -> flight.run("k", () -> {
                leaderRunning.countDown();
                await(release);
                return "value";
            }));
            await(leaderRunning);
            executor.submit(() -> {
                first.set(Thread.currentThread());
                return flight.run("k", () -> "first should not load");
            });
            Future<String> other = executor.submit(() -> {
                second.set(Thread.currentThread());
                return flight.run("k", () -> "second should not load");
            });
            awaitParked(first);
            awaitParked(second);

            first.get().interrupt();
            release.countDown();

            assertThat(leader.get()).isEqualTo("value");
            assertThat(other.get()).isEqualTo("value");
        }
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

            try {
                assertThat(fast.get(2, TimeUnit.SECONDS)).isEqualTo("b");
            } finally {
                release.countDown();
            }
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

    static void assertSameCause(Future<?> future, Throwable expected) {
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class).cause().isSameAs(expected);
    }

    static void awaitParked(AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (thread.get() == null && System.nanoTime() < deadline) {
            sleep(Duration.ofMillis(1));
        }
        awaitParked(Set.of(thread.get()), null);
    }

    // Waits until every thread except self is blocked. Gives up after 5s so a wrong implementation fails on
    // its assertions instead of hanging here.
    static void awaitParked(Set<Thread> threads, Thread self) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            boolean allParked = threads.stream()
                    .filter(thread -> thread != self)
                    .allMatch(thread -> thread.getState() == Thread.State.WAITING
                            || thread.getState() == Thread.State.TIMED_WAITING);
            if (allParked) {
                return;
            }
            sleep(Duration.ofMillis(1));
        }
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

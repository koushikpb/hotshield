package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ItemCacheTest {

    AtomicLong nanos = new AtomicLong();
    Ticker ticker = nanos::get;
    // Bounded and expiring like the real ones: Caffeine uses a different implementation for unbounded caches.
    ItemCache cache = new ItemCache(
            Caffeine.newBuilder().maximumSize(100).expireAfterWrite(Duration.ofMinutes(1)).ticker(ticker).build(),
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).ticker(ticker).build());

    @Test
    void staleReadAfterInvalidateIsRefused() {
        cache.invalidate(1, 2);

        assertThat(cache.put(item(1, 1))).isFalse();
        assertThat(cache.get(1)).isNull();
    }

    @Test
    void tombstoneStopsRefusingOnceItExpires() {
        cache.invalidate(1, 2);
        nanos.addAndGet(Duration.ofSeconds(11).toNanos());

        assertThat(cache.put(item(1, 1))).isTrue();
        assertThat(cache.floor(1)).isZero();
    }

    @Test
    void readOfTheWrittenVersionIsAccepted() {
        cache.invalidate(1, 2);

        cache.put(item(1, 2));

        assertThat(cache.get(1).version()).isEqualTo(2);
    }

    @Test
    void olderVersionDoesNotReplaceNewer() {
        cache.put(item(1, 3));

        assertThat(cache.put(item(1, 2))).isTrue();

        assertThat(cache.get(1).version()).isEqualTo(3);
    }

    @Test
    void unknownVersionBlocksEveryPut() {
        cache.invalidate(1, ItemCache.UNKNOWN_VERSION);

        cache.put(item(1, 1_000_000));

        assertThat(cache.get(1)).isNull();
    }

    @Test
    void tombstoneKeepsTheHighestVersion() {
        cache.invalidate(1, 5);
        cache.invalidate(1, 3);

        cache.put(item(1, 4));

        assertThat(cache.get(1)).isNull();
    }

    // The race this class exists for: a miss holding v1 and a write of v2 run at the same time. Whatever the
    // interleaving, v1 must not be in the cache afterwards.
    @RepeatedTest(200)
    void concurrentStalePutAndInvalidateNeverLeaveTheOldVersion() throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                ready.countDown();
                go.await();
                cache.put(item(1, 1));
                return null;
            });
            executor.submit(() -> {
                ready.countDown();
                go.await();
                cache.invalidate(1, 2);
                return null;
            });
            ready.await();
            go.countDown();
        }

        assertThat(cache.get(1)).isNull();
    }

    static Item item(long id, long version) {
        return new Item(id, "SKU-" + id, "kettle", "kitchen", 2500, 10, version, OffsetDateTime.now());
    }
}

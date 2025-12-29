package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.hotshield.HotShieldProperties;
import dev.hotshield.cluster.Cluster;
import dev.hotshield.cluster.OwnerClient;
import dev.hotshield.HotShieldProperties.Mode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClientException;
import org.junit.jupiter.api.Test;

class ItemServiceTest {

    ItemRepository repository = mock(ItemRepository.class);
    Cache<Long, Item> cache = Caffeine.newBuilder().build();

    Cluster cluster = mock(Cluster.class);
    OwnerClient owners = mock(OwnerClient.class);

    static HotShieldProperties properties(Mode mode) {
        return new HotShieldProperties(
                mode, "node-a", List.of(), Duration.ofMillis(500), Duration.ZERO, new HotShieldProperties.Cache(100, Duration.ofMinutes(1)));
    }

    ItemService service(Mode mode) {
        return new ItemService(repository, cache, properties(mode), cluster, owners, TransactionOperations.withoutTransaction());
    }

    @Test
    void directModeReadsTheDatabaseEveryTime() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.DIRECT);

        service.get(1);
        service.get(1);

        verify(repository, times(2)).findById(1);
    }

    @Test
    void cacheModeServesRepeatReadsFromCache() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.CACHE);

        var first = service.get(1);
        var second = service.get(1);

        assertThat(second).isEqualTo(first);
        verify(repository, times(1)).findById(1);
    }

    @Test
    void cacheModeDoesNotCacheMissingItems() {
        when(repository.findById(999)).thenReturn(Optional.empty());
        var service = service(Mode.CACHE);

        service.get(999);
        service.get(999);

        verify(repository, times(2)).findById(999);
    }

    @Test
    void updateInvalidatesTheCachedItem() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        when(repository.update(1, new ItemUpdate("new", 1, 1))).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.CACHE);
        service.get(1);
        assertThat(cache.getIfPresent(1L)).isNotNull();

        service.update(1, new ItemUpdate("new", 1, 1));
        service.get(1);

        verify(repository, times(2)).findById(1);
    }

    @Test
    void failedUpdateStillInvalidates() {
        cache.put(1L, ItemControllerTest.item(1));
        when(repository.update(any(Long.class), any())).thenThrow(new IllegalStateException("connection dropped"));
        var service = service(Mode.CACHE);

        assertThatThrownBy(() -> service.update(1, new ItemUpdate("new", 1, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.getIfPresent(1L)).isNull();
    }

    @Test
    void cacheModeRecordsHitsAndMisses() {
        var registry = new SimpleMeterRegistry();
        var properties = properties(Mode.CACHE);
        var meteredCache = new ItemCacheConfig().itemCache(properties, registry);
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = new ItemService(repository, meteredCache, properties, cluster, owners, TransactionOperations.withoutTransaction());

        service.get(1);
        service.get(1);

        assertThat(registry.get("cache.gets").tags("cache", "items", "result", "miss").functionCounter().count()).isEqualTo(1);
        assertThat(registry.get("cache.gets").tags("cache", "items", "result", "hit").functionCounter().count()).isEqualTo(1);
    }

    @Test
    void dbDelayRunsTheReadInsideATransaction() {
        var transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                invocation.<org.springframework.transaction.support.TransactionCallback<?>>getArgument(0).doInTransaction(null));
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var properties = new HotShieldProperties(Mode.DIRECT, "node-a", List.of(), Duration.ofMillis(500),
                Duration.ofMillis(20), new HotShieldProperties.Cache(100, Duration.ofMinutes(1)));
        var service = new ItemService(repository, cache, properties, cluster, owners, transactions);

        assertThat(service.get(1)).isPresent();
        verify(transactions).execute(any());
    }

    // The distributed tests below go through SingleFlight on a miss, so they fail until it is written.
    @Test
    void ownerReadsLocally() {
        when(cluster.ownerOf(1)).thenReturn("node-a");
        when(cluster.isSelf("node-a")).thenReturn(true);
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));

        assertThat(service(Mode.DISTRIBUTED).get(1)).isPresent();
        verify(owners, never()).fetch(any(), any(Long.class));
    }

    @Test
    void nonOwnerForwardsMissAndCachesTheResult() {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        when(owners.fetch("http://node-b:8080", 1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.DISTRIBUTED);

        service.get(1);
        service.get(1);

        verify(owners, times(1)).fetch("http://node-b:8080", 1);
        verify(repository, never()).findById(1);
    }

    @Test
    void nonOwnerFallsBackToDatabaseWhenOwnerFails() {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        when(owners.fetch("http://node-b:8080", 1)).thenThrow(new RestClientException("timed out"));
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));

        assertThat(service(Mode.DISTRIBUTED).get(1)).isPresent();
        verify(repository).findById(1);
    }

    @Test
    void ownerEndpointNeverForwards() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));

        assertThat(service(Mode.DISTRIBUTED).getAsOwner(1)).isPresent();
        verify(owners, never()).fetch(any(), any(Long.class));
    }

    // Fails until SingleFlight is written (milestone 3).
    @Test
    void coalesceModeSharesOneReadAcrossConcurrentMisses() throws Exception {
        int callers = 1_000;
        var allStarted = new CountDownLatch(callers);
        when(repository.findById(1)).thenAnswer(invocation -> {
            allStarted.await();
            Thread.sleep(200);
            return Optional.of(ItemControllerTest.item(1));
        });
        var service = service(Mode.COALESCE);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                executor.submit(() -> {
                    allStarted.countDown();
                    return service.get(1);
                });
            }
        }

        verify(repository, times(1)).findById(1);
    }
}

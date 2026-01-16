package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.hotshield.HotShieldProperties;
import dev.hotshield.cluster.Cluster;
import dev.hotshield.cluster.PeerClient;
import dev.hotshield.HotShieldProperties.Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClientException;
import org.junit.jupiter.api.Test;

class ItemServiceTest {

    ItemRepository repository = mock(ItemRepository.class);
    ItemCache cache = new ItemCache(Caffeine.newBuilder().maximumSize(100).build(), Caffeine.newBuilder().maximumSize(100).build());

    Cluster cluster = mock(Cluster.class);
    PeerClient peers = mock(PeerClient.class);

    static HotShieldProperties properties(Mode mode) {
        return new HotShieldProperties(
                mode, "node-a", List.of(), Duration.ofMillis(500), Duration.ZERO, new HotShieldProperties.Cache(100, Duration.ofMinutes(1), Duration.ofSeconds(10)));
    }

    ItemService service(Mode mode) {
        return new ItemService(repository, cache, properties(mode), cluster, peers, TransactionOperations.withoutTransaction(), new SimpleMeterRegistry());
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
        assertThat(cache.get(1L)).isNotNull();

        service.update(1, new ItemUpdate("new", 1, 1));
        service.get(1);

        verify(repository, times(2)).findById(1);
    }

    @Test
    void failedUpdateStillInvalidates() {
        cache.put(ItemControllerTest.item(1));
        when(repository.update(any(Long.class), any())).thenThrow(new IllegalStateException("connection dropped"));
        var service = service(Mode.CACHE);

        assertThatThrownBy(() -> service.update(1, new ItemUpdate("new", 1, 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(1L)).isNull();
    }

    @Test
    void cacheModeRecordsHitsAndMisses() {
        var registry = new SimpleMeterRegistry();
        var properties = properties(Mode.CACHE);
        var meteredCache = new ItemCacheConfig().itemCache(properties, registry);
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = new ItemService(repository, meteredCache, properties, cluster, peers, TransactionOperations.withoutTransaction(), registry);

        service.get(1);
        service.get(1);

        assertThat(registry.get("cache.gets").tags("cache", "items", "result", "miss").functionCounter().count()).isEqualTo(1);
        assertThat(registry.get("cache.gets").tags("cache", "items", "result", "hit").functionCounter().count()).isEqualTo(1);
    }

    @Test
    void dbDelayRunsTheReadInsideATransaction() {
        var transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var properties = new HotShieldProperties(Mode.DIRECT, "node-a", List.of(), Duration.ofMillis(500),
                Duration.ofMillis(20), new HotShieldProperties.Cache(100, Duration.ofMinutes(1), Duration.ofSeconds(10)));
        var service = new ItemService(repository, cache, properties, cluster, peers, transactions, new SimpleMeterRegistry());

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
        verify(peers, never()).fetch(any(), any(Long.class));
    }

    @Test
    void nonOwnerForwardsMissAndCachesTheResult() {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        when(peers.fetch("http://node-b:8080", 1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.DISTRIBUTED);

        service.get(1);
        service.get(1);

        verify(peers, times(1)).fetch("http://node-b:8080", 1);
        verify(repository, never()).findById(1);
    }

    @Test
    void nonOwnerFallsBackToDatabaseWhenOwnerFails() {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        when(peers.fetch("http://node-b:8080", 1)).thenThrow(new RestClientException("timed out"));
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));

        assertThat(service(Mode.DISTRIBUTED).get(1)).isPresent();
        verify(repository).findById(1);
    }

    // Membership disagreement: this node forwards id 1 to node-b while node-b forwards id 1 here. The incoming
    // owner request must not wait on the outgoing forward.
    @Test
    void ownerRequestDoesNotWaitOnThisNodesOwnForward() throws Exception {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        var forwardStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(peers.fetch("http://node-b:8080", 1)).thenAnswer(invocation -> {
            forwardStarted.countDown();
            release.await();
            return Optional.of(ItemControllerTest.item(1));
        });
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));
        var service = service(Mode.DISTRIBUTED);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> service.get(1));
            try {
                assertThat(forwardStarted.await(2, TimeUnit.SECONDS)).isTrue();

                var owner = executor.submit(() -> service.getAsOwner(1));

                assertThat(owner.get(2, TimeUnit.SECONDS)).isPresent();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void ownerEndpointNeverForwards() {
        when(repository.findById(1)).thenReturn(Optional.of(ItemControllerTest.item(1)));

        assertThat(service(Mode.DISTRIBUTED).getAsOwner(1)).isPresent();
        verify(peers, never()).fetch(any(), any(Long.class));
    }

    @Test
    void coalesceModeSharesOneReadAcrossConcurrentMisses() throws Exception {
        int callers = 1_000;
        var allStarted = new CountDownLatch(callers);
        var item = ItemControllerTest.item(1);
        when(repository.findById(1)).thenAnswer(invocation -> {
            allStarted.await();
            Thread.sleep(200);
            return Optional.of(item);
        });
        var service = service(Mode.COALESCE);

        var results = new ArrayList<Future<Optional<Item>>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    allStarted.countDown();
                    return service.get(1);
                }));
            }
        }

        verify(repository, times(1)).findById(1);
        for (var result : results) {
            assertThat(result.get()).contains(item);
        }
    }

    // Missing ids aren't cached, so each new wave of misses for an absent id still costs one read.
    @Test
    void coalesceModeSharesOneReadForAnAbsentId() throws Exception {
        int callers = 1_000;
        var allStarted = new CountDownLatch(callers);
        when(repository.findById(999)).thenAnswer(invocation -> {
            allStarted.await();
            Thread.sleep(200);
            return Optional.empty();
        });
        var service = service(Mode.COALESCE);

        var results = new ArrayList<Future<Optional<Item>>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    allStarted.countDown();
                    return service.get(999);
                }));
            }
        }

        verify(repository, times(1)).findById(999);
        for (var result : results) {
            assertThat(result.get()).isEmpty();
        }
        assertThat(cache.get(999)).isNull();
    }

    @Test
    void distributedUpdateInvalidatesEveryPeerWithTheNewVersion() {
        when(cluster.peerUrls()).thenReturn(List.of("http://node-b:8080", "http://node-c:8080"));
        when(repository.update(1, new ItemUpdate("new", 1, 1))).thenReturn(Optional.of(ItemControllerTest.item(1)));

        service(Mode.DISTRIBUTED).update(1, new ItemUpdate("new", 1, 1));

        verify(peers).invalidate("http://node-b:8080", 1, 3);
        verify(peers).invalidate("http://node-c:8080", 1, 3);
    }

    @Test
    void unreachablePeerDoesNotFailTheWrite() {
        when(cluster.peerUrls()).thenReturn(List.of("http://node-b:8080"));
        when(repository.update(1, new ItemUpdate("new", 1, 1))).thenReturn(Optional.of(ItemControllerTest.item(1)));
        doThrow(new RestClientException("refused")).when(peers).invalidate(any(), any(Long.class), any(Long.class));

        assertThat(service(Mode.DISTRIBUTED).update(1, new ItemUpdate("new", 1, 1))).isPresent();
    }

    @Test
    void updateOfMissingIdBroadcastsNothing() {
        when(cluster.peerUrls()).thenReturn(List.of("http://node-b:8080"));
        when(repository.update(999, new ItemUpdate("new", 1, 1))).thenReturn(Optional.empty());

        service(Mode.DISTRIBUTED).update(999, new ItemUpdate("new", 1, 1));

        verify(peers, never()).invalidate(any(), any(Long.class), any(Long.class));
    }

    // The owner missed the invalidation for version 5 and still serves version 3. This node must not return or
    // cache version 3, and must tell the owner what it missed.
    @Test
    void staleOwnerResultIsReplacedByADatabaseReadAndTheOwnerIsRepaired() {
        when(cluster.ownerOf(1)).thenReturn("node-b");
        when(cluster.baseUrl("node-b")).thenReturn("http://node-b:8080");
        cache.invalidate(1, 5);
        when(peers.fetch("http://node-b:8080", 1)).thenReturn(Optional.of(ItemCacheTest.item(1, 3)));
        when(repository.findById(1)).thenReturn(Optional.of(ItemCacheTest.item(1, 5)));

        var result = service(Mode.DISTRIBUTED).get(1);

        assertThat(result.orElseThrow().version()).isEqualTo(5);
        assertThat(cache.get(1).version()).isEqualTo(5);
        verify(peers).invalidate("http://node-b:8080", 1, 5);
    }
}

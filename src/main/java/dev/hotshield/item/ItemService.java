package dev.hotshield.item;

import dev.hotshield.HotShieldProperties;
import dev.hotshield.cluster.Cluster;
import dev.hotshield.cluster.PeerClient;
import dev.hotshield.coalesce.SingleFlight;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository repository;
    private final ItemCache cache;
    private final HotShieldProperties.Mode mode;
    private final Cluster cluster;
    private final PeerClient peers;
    private final TransactionOperations transactions;
    private final Duration dbDelay;
    private final Duration forwardTimeout;
    // Two instances on purpose. If two nodes each think the other owns a key, each forwards it; with one shared
    // instance the incoming /internal request would join the node's own outgoing forward and both would wait
    // on each other until the forward timeout.
    private final SingleFlight<FlightKey, Optional<Item>> loads = new SingleFlight<>();
    private final SingleFlight<FlightKey, Optional<Item>> forwards = new SingleFlight<>();
    private final Counter forwardOk;
    private final Counter forwardFallback;

    public ItemService(
            ItemRepository repository,
            ItemCache cache,
            HotShieldProperties properties,
            Cluster cluster,
            PeerClient peers,
            TransactionOperations transactions,
            MeterRegistry registry) {
        this.repository = repository;
        this.cache = cache;
        this.mode = properties.mode();
        this.cluster = cluster;
        this.peers = peers;
        this.transactions = transactions;
        this.dbDelay = properties.dbDelay();
        this.forwardTimeout = properties.forwardTimeout();
        // Separates owner reads from fallback reads, which db.queries alone can't.
        this.forwardOk = Counter.builder("owner.forwards").tag("result", "ok").register(registry);
        this.forwardFallback = Counter.builder("owner.forwards").tag("result", "fallback").register(registry);
    }

    public Optional<Item> get(long id) {
        return switch (mode) {
            case DIRECT -> readDb(id);
            case CACHE -> getCacheAside(id);
            case COALESCE -> getCoalesced(id);
            case DISTRIBUTED -> getDistributed(id);
        };
    }

    // Serves /internal/items/{id}. Never forwards again: if two nodes disagree about the owner, a forward
    // still ends here instead of bouncing between them.
    public Optional<Item> getAsOwner(long id) {
        return getCoalesced(id);
    }

    // Plain cache-aside on purpose: cache.get(id, loader) would already coalesce concurrent misses per key,
    // and then this mode would measure the same thing as coalesce mode.
    private Optional<Item> getCacheAside(long id) {
        Item cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Item> loaded = readDb(id);
        loaded.ifPresent(cache::put);
        return loaded;
    }

    // Same as cache-aside, except concurrent misses for one id share a single database read.
    private Optional<Item> getCoalesced(long id) {
        Item cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        return loadOnce(id);
    }

    @WithSpan("singleflight.load")
    Optional<Item> loadOnce(@SpanAttribute("item.id") long id) {
        return loads.run(flightKey(id), () -> {
            // Another flight may have filled the cache between our miss and starting this one.
            Item filled = cache.get(id);
            if (filled != null) {
                return Optional.of(filled);
            }
            Optional<Item> loaded = readDb(id);
            loaded.ifPresent(cache::put);
            return loaded;
        });
    }

    // A flight that started before a write can finish with the old row. Keying flights by the invalidation floor
    // means a read that arrives after the write starts a new flight instead of joining the old one, so a writer
    // always reads its own write.
    private FlightKey flightKey(long id) {
        return new FlightKey(id, cache.floor(id));
    }

    private record FlightKey(long id, long floor) {}

    // Non-owners keep their own copy and forward only misses. Forwards for one id are coalesced here too,
    // so a hot key costs each non-owner one forward per miss, not one per request.
    private Optional<Item> getDistributed(long id) {
        String owner = cluster.ownerOf(id);
        if (cluster.isSelf(owner)) {
            return getCoalesced(id);
        }
        Item cached = cache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        return forwardOnce(owner, id);
    }

    @WithSpan("singleflight.forward")
    Optional<Item> forwardOnce(@SpanAttribute("owner") String owner, @SpanAttribute("item.id") long id) {
        return forwards.run(flightKey(id), () -> {
            Item filled = cache.get(id);
            if (filled != null) {
                return Optional.of(filled);
            }
            Optional<Item> loaded = fetchFromOwner(owner, id);
            loaded.ifPresent(cache::put);
            return loaded;
        });
    }

    // Falling back to the database keeps reads available when the owner is down, at the cost of losing
    // cross-node coalescing for that key until it recovers.
    @WithSpan("owner.forward")
    Optional<Item> fetchFromOwner(@SpanAttribute("owner") String owner, @SpanAttribute("item.id") long id) {
        try {
            Optional<Item> item = peers.fetch(cluster.baseUrl(owner), id);
            forwardOk.increment();
            return item;
        } catch (RestClientException e) {
            forwardFallback.increment();
            log.warn("owner fetch failed, reading db owner={} key={} error={}", owner, id, e.getMessage());
            return readDb(id);
        }
    }

    // A local findById takes about a millisecond, which leaves almost no window for concurrent misses to
    // pile up, so the herd experiment can add a delay. It sleeps inside a transaction so the pooled connection
    // stays checked out, like a slow query would; a sleep outside it wouldn't touch the pool at all.
    @WithSpan("db.read")
    Optional<Item> readDb(@SpanAttribute("item.id") long id) {
        if (dbDelay.isZero()) {
            return repository.findById(id);
        }
        return transactions.execute(status -> {
            status.setRollbackOnly();
            sleep(dbDelay);
            return repository.findById(id);
        });
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during db delay", e);
        }
    }

    // Invalidate after the write, even if it threw: a dropped connection can still mean the UPDATE committed,
    // and then the new version is unknown.
    public Optional<Item> update(long id, ItemUpdate update) {
        Optional<Item> updated;
        try {
            updated = repository.update(id, update);
        } catch (RuntimeException e) {
            invalidateEverywhere(id, ItemCache.UNKNOWN_VERSION);
            throw e;
        }
        invalidateEverywhere(id, updated.map(Item::version).orElse(ItemCache.UNKNOWN_VERSION));
        return updated;
    }

    public void invalidateLocal(long id, long version) {
        cache.invalidate(id, version);
    }

    // The node that took the write tells every peer directly instead of routing through the owner, which would
    // add a hop to every PUT. Waits at most forwardTimeout so the PUT's latency stays bounded; a peer that
    // misses the message serves its old copy until the TTL, which is what the staleness experiment measures.
    private void invalidateEverywhere(long id, long version) {
        cache.invalidate(id, version);
        if (mode != HotShieldProperties.Mode.DISTRIBUTED) {
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> sent = cluster.peerUrls().stream()
                    .<Future<?>>map(url -> executor.submit(() -> peers.invalidate(url, id, version)))
                    .toList();
            long deadline = System.nanoTime() + forwardTimeout.toNanos();
            for (Future<?> send : sent) {
                awaitInvalidation(send, deadline, id);
            }
        }
    }

    private static void awaitInvalidation(Future<?> send, long deadline, long id) {
        try {
            send.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException e) {
            send.cancel(true);
            log.warn("invalidate broadcast failed key={} error={}", id, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        return repository.findByCategory(category, minPriceCents, limit);
    }
}

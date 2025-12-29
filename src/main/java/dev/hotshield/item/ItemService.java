package dev.hotshield.item;

import com.github.benmanes.caffeine.cache.Cache;
import dev.hotshield.HotShieldProperties;
import dev.hotshield.cluster.Cluster;
import dev.hotshield.cluster.OwnerClient;
import dev.hotshield.coalesce.SingleFlight;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    private static final Logger log = LoggerFactory.getLogger(ItemService.class);

    private final ItemRepository repository;
    private final Cache<Long, Item> cache;
    private final HotShieldProperties.Mode mode;
    private final Cluster cluster;
    private final OwnerClient owners;
    private final SingleFlight<Long, Optional<Item>> misses = new SingleFlight<>();

    public ItemService(
            ItemRepository repository,
            Cache<Long, Item> cache,
            HotShieldProperties properties,
            Cluster cluster,
            OwnerClient owners) {
        this.repository = repository;
        this.cache = cache;
        this.mode = properties.mode();
        this.cluster = cluster;
        this.owners = owners;
    }

    public Optional<Item> get(long id) {
        return switch (mode) {
            case DIRECT -> repository.findById(id);
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
        Item cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Item> loaded = repository.findById(id);
        loaded.ifPresent(item -> cache.put(id, item));
        return loaded;
    }

    // Same as cache-aside, except concurrent misses for one id share a single database read.
    private Optional<Item> getCoalesced(long id) {
        Item cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        return misses.run(id, () -> {
            Optional<Item> loaded = repository.findById(id);
            loaded.ifPresent(item -> cache.put(id, item));
            return loaded;
        });
    }

    // Non-owners keep their own copy and forward only misses. Forwards for one id are coalesced here too,
    // so a hot key costs each non-owner one forward per miss, not one per request.
    private Optional<Item> getDistributed(long id) {
        String owner = cluster.ownerOf(id);
        if (cluster.isSelf(owner)) {
            return getCoalesced(id);
        }
        Item cached = cache.getIfPresent(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        return misses.run(id, () -> {
            Optional<Item> loaded = fetchFromOwner(owner, id);
            loaded.ifPresent(item -> cache.put(id, item));
            return loaded;
        });
    }

    // Falling back to the database keeps reads available when the owner is down, at the cost of losing
    // cross-node coalescing for that key until it recovers.
    private Optional<Item> fetchFromOwner(String owner, long id) {
        try {
            return owners.fetch(cluster.baseUrl(owner), id);
        } catch (RestClientException e) {
            log.warn("owner fetch failed, reading db owner={} key={} error={}", owner, id, e.getMessage());
            return repository.findById(id);
        }
    }

    // Invalidate after the write, even if it threw: a dropped connection can still mean the UPDATE committed.
    // A miss that read the old row before this can still put it back; milestone 6 closes that race.
    public Optional<Item> update(long id, ItemUpdate update) {
        try {
            return repository.update(id, update);
        } finally {
            cache.invalidate(id);
        }
    }

    public List<Item> findByCategory(String category, int minPriceCents, int limit) {
        return repository.findByCategory(category, minPriceCents, limit);
    }
}

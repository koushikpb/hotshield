package dev.hotshield.item;

import com.github.benmanes.caffeine.cache.Cache;
import dev.hotshield.HotShieldProperties;
import dev.hotshield.coalesce.SingleFlight;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    private final ItemRepository repository;
    private final Cache<Long, Item> cache;
    private final HotShieldProperties.Mode mode;
    private final SingleFlight<Long, Optional<Item>> misses = new SingleFlight<>();

    public ItemService(ItemRepository repository, Cache<Long, Item> cache, HotShieldProperties properties) {
        this.repository = repository;
        this.cache = cache;
        this.mode = properties.mode();
    }

    public Optional<Item> get(long id) {
        return switch (mode) {
            case DIRECT -> repository.findById(id);
            case CACHE -> getCacheAside(id);
            case COALESCE -> getCoalesced(id);
            case DISTRIBUTED -> throw new UnsupportedOperationException("mode not built yet: " + mode);
        };
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

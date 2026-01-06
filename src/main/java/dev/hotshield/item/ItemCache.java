package dev.hotshield.item;

import com.github.benmanes.caffeine.cache.Cache;

// The item cache plus short-lived tombstones that close the stale-put race: a miss reads v1, a write commits v2
// and invalidates, then the miss tries to cache v1. The tombstone remembers v2 long enough to refuse that put.
// put and invalidate both run inside compute() on the same key, so a put can't slip between an invalidate's
// tombstone write and its removal.
public final class ItemCache {

    // Version to record when a write's outcome is unknown: refuses every put until the tombstone expires.
    public static final long UNKNOWN_VERSION = Long.MAX_VALUE;

    private final Cache<Long, Item> items;
    private final Cache<Long, Long> tombstones;

    public ItemCache(Cache<Long, Item> items, Cache<Long, Long> tombstones) {
        this.items = items;
        this.tombstones = tombstones;
    }

    public Item get(long id) {
        return items.getIfPresent(id);
    }

    // The version a fresh read must reach for this id; 0 when nothing was invalidated recently.
    public long floor(long id) {
        Long floor = tombstones.getIfPresent(id);
        return floor == null ? 0 : floor;
    }

    public void put(Item item) {
        items.asMap().compute(item.id(), (id, current) -> {
            Long floor = tombstones.getIfPresent(id);
            if (floor != null && item.version() < floor) {
                return current;
            }
            if (current != null && current.version() > item.version()) {
                return current;
            }
            return item;
        });
    }

    public void invalidate(long id, long version) {
        items.asMap().compute(id, (key, current) -> {
            tombstones.asMap().merge(key, version, Math::max);
            return null;
        });
    }
}

package dev.hotshield.item;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.hotshield.HotShieldProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ItemCacheConfig {

    @Bean
    ItemCache itemCache(HotShieldProperties properties, MeterRegistry registry) {
        Cache<Long, Item> items = Caffeine.newBuilder()
                .maximumSize(properties.cache().maxSize())
                .expireAfterWrite(properties.cache().ttl())
                .recordStats()
                .build();
        // Exposes cache.gets{result=hit|miss}, cache.evictions, cache.size under cache=items.
        CaffeineCacheMetrics.monitor(registry, items, "items");
        // A tombstone must outlive any read that started before the write, so it is sized from the slowest
        // read path: a forward that times out and falls back to the database.
        Cache<Long, Long> tombstones = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().tombstoneTtl())
                .build();
        return new ItemCache(items, tombstones);
    }
}

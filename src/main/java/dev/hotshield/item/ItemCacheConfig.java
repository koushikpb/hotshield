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
    Cache<Long, Item> itemCache(HotShieldProperties properties, MeterRegistry registry) {
        Cache<Long, Item> cache = Caffeine.newBuilder()
                .maximumSize(properties.cache().maxSize())
                .expireAfterWrite(properties.cache().ttl())
                .recordStats()
                .build();
        // Exposes cache.gets{result=hit|miss}, cache.evictions, cache.size under cache=items.
        return CaffeineCacheMetrics.monitor(registry, cache, "items");
    }
}

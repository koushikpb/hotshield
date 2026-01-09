package dev.hotshield.item;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.hotshield.HotShieldProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ItemCacheConfig {

    @Bean
    ItemCache itemCache(
            HotShieldProperties properties,
            MeterRegistry registry,
            @Value("${spring.datasource.hikari.connection-timeout}") Duration connectionTimeout,
            @Value("${hotshield.db-statement-timeout}") Duration statementTimeout) {
        checkTombstoneTtl(properties, connectionTimeout, statementTimeout);
        return itemCache(properties, registry);
    }

    // A tombstone must outlive any read that started before the write, or that read can cache the old row once
    // the tombstone is gone. The slowest read is a forward that times out (connect, then the exchange), falls
    // back to the database, waits for a pooled connection, sleeps the herd delay, and runs to the statement
    // timeout.
    static void checkTombstoneTtl(HotShieldProperties properties, Duration connectionTimeout, Duration statementTimeout) {
        Duration slowestRead = properties.forwardTimeout().multipliedBy(2)
                .plus(connectionTimeout)
                .plus(properties.dbDelay())
                .plus(statementTimeout);
        if (properties.cache().tombstoneTtl().compareTo(slowestRead) <= 0) {
            throw new IllegalStateException("hotshield.cache.tombstone-ttl must exceed the slowest read path ("
                    + slowestRead + "), got " + properties.cache().tombstoneTtl());
        }
    }

    ItemCache itemCache(HotShieldProperties properties, MeterRegistry registry) {
        Cache<Long, Item> items = Caffeine.newBuilder()
                .maximumSize(properties.cache().maxSize())
                .expireAfterWrite(properties.cache().ttl())
                .recordStats()
                .build();
        // Exposes cache.gets{result=hit|miss}, cache.evictions, cache.size under cache=items.
        CaffeineCacheMetrics.monitor(registry, items, "items");
        Cache<Long, Long> tombstones = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().tombstoneTtl())
                .build();
        return new ItemCache(items, tombstones);
    }
}

package dev.hotshield.item;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hotshield.HotShieldProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItemCacheConfigTest {

    @Test
    void defaultsLeaveRoomForTheSlowestRead() {
        assertThatCode(() -> ItemCacheConfig.checkTombstoneTtl(
                properties(Duration.ZERO, Duration.ofSeconds(10)), Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .doesNotThrowAnyException();
    }

    // A long herd delay makes reads slower than the tombstone lives; that must fail at startup, not silently.
    @Test
    void longDbDelayWithDefaultTombstoneFailsStartup() {
        assertThatThrownBy(() -> ItemCacheConfig.checkTombstoneTtl(
                properties(Duration.ofSeconds(8), Duration.ofSeconds(10)), Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    static HotShieldProperties properties(Duration dbDelay, Duration tombstoneTtl) {
        return new HotShieldProperties(HotShieldProperties.Mode.DISTRIBUTED, "node-a", List.of(), Duration.ofMillis(500),
                dbDelay, new HotShieldProperties.Cache(100, Duration.ofSeconds(30), tombstoneTtl));
    }
}

package dev.hotshield;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hotshield")
public record HotShieldProperties(
        Mode mode, String nodeId, List<String> peers, Duration forwardTimeout, Duration dbDelay, Cache cache) {

    public enum Mode { DIRECT, CACHE, COALESCE, DISTRIBUTED }

    public record Cache(long maxSize, Duration ttl) {}
}

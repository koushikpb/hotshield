package dev.hotshield.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hotshield.HotShieldProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClusterTest {

    @Test
    void parsesIdUrlPairsInOrder() {
        var urls = Cluster.parse(List.of("node-a=http://a:8080", " node-b = http://b:8080 "));

        assertThat(urls).containsExactly(
                java.util.Map.entry("node-a", "http://a:8080"), java.util.Map.entry("node-b", "http://b:8080"));
    }

    @Test
    void rejectsPeerWithoutId() {
        assertThatThrownBy(() -> Cluster.parse(List.of("http://a:8080"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPeersThatLeaveOutThisNode() {
        var properties = new HotShieldProperties(HotShieldProperties.Mode.DISTRIBUTED, "node-a",
                List.of("node-b=http://b:8080"), Duration.ofMillis(500), Duration.ZERO,
                new HotShieldProperties.Cache(10, Duration.ofSeconds(30), Duration.ofSeconds(10)));

        assertThatThrownBy(() -> new Cluster(properties)).isInstanceOf(IllegalArgumentException.class);
    }
}

package dev.hotshield.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Fails until you write RendezvousHash (milestone 5).
class RendezvousHashTest {

    static final List<String> THREE = List.of("node-a", "node-b", "node-c");
    static final int KEYS = 300_000;

    @Test
    void singleNodeOwnsEveryKey() {
        var hash = new RendezvousHash(List.of("node-a"));

        for (long key = 0; key < 1_000; key++) {
            assertThat(hash.ownerOf(key)).isEqualTo("node-a");
        }
    }

    // Every node builds its own instance, possibly from a differently ordered list. They must agree.
    @Test
    void ownerDoesNotDependOnInstanceOrNodeOrder() {
        var first = new RendezvousHash(THREE);
        var reordered = new RendezvousHash(List.of("node-c", "node-a", "node-b"));

        for (long key = 0; key < 10_000; key++) {
            assertThat(reordered.ownerOf(key)).isEqualTo(first.ownerOf(key));
        }
    }

    @Test
    void keysSpreadEvenlyAcrossNodes() {
        var counts = countOwners(new RendezvousHash(THREE));

        for (String node : THREE) {
            assertThat(counts.get(node)).isBetween((int) (KEYS * 0.31), (int) (KEYS * 0.36));
        }
    }

    // Sequential ids are what the load test uses; a weak hash can map runs of them to one node.
    @Test
    void consecutiveKeysAreNotClustered() {
        var hash = new RendezvousHash(THREE);
        int sameAsPrevious = 0;
        for (long key = 1; key < 10_000; key++) {
            if (hash.ownerOf(key).equals(hash.ownerOf(key - 1))) {
                sameAsPrevious++;
            }
        }

        assertThat(sameAsPrevious).isBetween(2_900, 3_800);
    }

    // The property that makes rendezvous hashing worth it: removing a node moves only that node's keys.
    @Test
    void removingANodeMovesOnlyItsKeys() {
        var before = new RendezvousHash(THREE);
        var after = new RendezvousHash(List.of("node-a", "node-c"));

        for (long key = 0; key < KEYS; key++) {
            String owner = before.ownerOf(key);
            if (!owner.equals("node-b")) {
                assertThat(after.ownerOf(key)).isEqualTo(owner);
            }
        }
    }

    @Test
    void addingANodeMovesAboutAQuarterOfKeysAllToTheNewNode() {
        var before = new RendezvousHash(THREE);
        var after = new RendezvousHash(List.of("node-a", "node-b", "node-c", "node-d"));
        int moved = 0;

        for (long key = 0; key < KEYS; key++) {
            String was = before.ownerOf(key);
            String now = after.ownerOf(key);
            if (!was.equals(now)) {
                assertThat(now).isEqualTo("node-d");
                moved++;
            }
        }

        assertThat(moved).isBetween((int) (KEYS * 0.22), (int) (KEYS * 0.28));
    }

    static Map<String, Integer> countOwners(RendezvousHash hash) {
        var counts = new HashMap<String, Integer>();
        for (long key = 0; key < KEYS; key++) {
            counts.merge(hash.ownerOf(key), 1, Integer::sum);
        }
        return counts;
    }
}

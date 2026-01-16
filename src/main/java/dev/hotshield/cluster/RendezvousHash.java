package dev.hotshield.cluster;

import java.nio.charset.StandardCharsets;
import java.util.List;

// Picks the owner of a key: score every node against the key and take the highest. Every node computes the
// same answer from the same node list, with no coordination. Removing a node only moves the keys it owned,
// because every other node's score for a key stays the same.
public final class RendezvousHash {

    private final String[] nodes;
    private final long[] seeds;

    public RendezvousHash(List<String> nodes) {
        this.nodes = nodes.toArray(String[]::new);
        this.seeds = new long[this.nodes.length];
        for (int i = 0; i < this.nodes.length; i++) {
            seeds[i] = seed(this.nodes[i]);
        }
    }

    // A linear scan is fine for three nodes. With hundreds, a ring or a skeleton tree would beat O(nodes).
    public String ownerOf(long key) {
        long mixedKey = mix(key);
        String best = null;
        long bestScore = 0;
        for (int i = 0; i < nodes.length; i++) {
            long score = mix(mixedKey ^ seeds[i]);
            // Ties are broken by name, not list position, so a differently ordered list gives the same owner.
            if (best == null || Long.compareUnsigned(score, bestScore) > 0
                    || (score == bestScore && nodes[i].compareTo(best) < 0)) {
                best = nodes[i];
                bestScore = score;
            }
        }
        return best;
    }

    // String.hashCode is too weak here: the hashes of "node-a" and "node-b" differ by one. FNV-1a over the bytes,
    // then a mix, gives seeds far apart.
    private static long seed(String node) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : node.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b;
            hash *= 0x100000001b3L;
        }
        return mix(hash);
    }

    // MurmurHash3's 64-bit finalizer. Sequential ids differ only in their low bits; this spreads every input
    // bit across the whole output, so runs of ids don't land on one node.
    private static long mix(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
